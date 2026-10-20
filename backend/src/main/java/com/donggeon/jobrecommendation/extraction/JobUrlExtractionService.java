package com.donggeon.jobrecommendation.extraction;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns a posting address into text for the user to review. HTML gives its visible text, headed by the page
 * title and site name so the company can be cited from the text; when a page holds little text (postings are
 * often one tall image), its content images are read with OCR in page order. A linked PDF or image is read
 * like an uploaded file.
 */
@Service
public class JobUrlExtractionService {
    private static final Logger log = LoggerFactory.getLogger(JobUrlExtractionService.class);
    static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
    static final int MAX_IMAGES = 15;
    static final int MAX_FRAMES = 3;
    /** Below this much page text, the content images are read too. */
    static final int ENOUGH_TEXT = 800;
    private static final int MIN_IMAGE_WIDTH = 300;
    private static final int MIN_IMAGE_HEIGHT = 80;
    private static final Duration BUDGET = Duration.ofSeconds(120);
    private static final String ACCEPT_PAGE = "text/html,application/xhtml+xml,application/pdf;q=0.9,image/*;q=0.8,*/*;q=0.5";
    private static final String ACCEPT_IMAGE = "image/png,image/jpeg,image/webp;q=0.9,*/*;q=0.5";
    private static final Pattern DECORATION = Pattern.compile("logo|icon|btn|button|bullet|blank|spacer|arrow");
    private static final Set<String> HTML = Set.of("text/html", "application/xhtml+xml", "");

    private final WebPageFetcher fetcher;
    private final JobTextExtractionService files;

    public record Result(String text, String method, int pages, boolean truncated, String sourceUrl) {}

    /** What is being read now: PAGE (the page and its frames), FILE (a linked PDF or image) or IMAGES, done of total. */
    public record Progress(String stage, int done, int total) {}

    public JobUrlExtractionService(WebPageFetcher fetcher, JobTextExtractionService files) {
        this.fetcher = fetcher;
        this.files = files;
    }

    public Result extract(String address) {
        return extract(address, progress -> { });
    }

    /** Like {@link #extract(String)}, telling {@code progress} as each stage starts and after each image is read. */
    public Result extract(String address, Consumer<Progress> progress) {
        URI uri = target(address);
        try (var session = fetcher.open(BUDGET)) {
            progress.accept(new Progress("PAGE", 0, 1));
            var page = session.get(uri, MAX_PAGE_BYTES, ACCEPT_PAGE);
            String suffix = fileSuffix(page.contentType(), page.body());
            JobTextExtractionService.Result result;
            if (suffix != null) {
                progress.accept(new Progress("FILE", 0, 1));
                var file = files.extract(page.body(), suffix);
                result = new JobTextExtractionService.Result(file.text(), suffix.equals("pdf") ? "URL_PDF" : "URL_OCR",
                        file.pages(), file.truncated());
            } else if (HTML.contains(page.contentType())) {
                result = html(session, page, progress);
            } else {
                throw unreadable();
            }
            log.info("url_extracted host={} method={} pages={}", page.uri().getHost(), result.method(), result.pages());
            return new Result(result.text(), result.method(), result.pages(), result.truncated(), uri.toString());
        }
    }

    /** The address as it will be requested; refuses one that cannot be fetched before any work starts. */
    public static URI target(String address) {
        URI uri = WebPageFetcher.parse(address);
        WebPageFetcher.checkTarget(uri);
        return uri;
    }

    private JobTextExtractionService.Result html(WebPageFetcher.Session session, WebPageFetcher.Page page,
                                                 Consumer<Progress> progress) {
        Document document = parse(page);
        String heading = heading(document);
        Element root = content(document);
        StringBuilder text = new StringBuilder(blockText(root));
        List<URI> images = new ArrayList<>(imageAddresses(root));

        // A page that is mostly a frame (some boards show the posting body that way) gets its same-site frames read.
        if (text.length() < ENOUGH_TEXT) {
            for (URI frame : frameAddresses(root, page.uri())) {
                try {
                    var framed = session.get(frame, MAX_PAGE_BYTES, ACCEPT_PAGE);
                    if (!HTML.contains(framed.contentType())) continue;
                    Element frameRoot = content(parse(framed));
                    text.append('\n').append(blockText(frameRoot));
                    images.addAll(imageAddresses(frameRoot));
                } catch (ResponseStatusException ex) {
                    log.info("url_frame_skipped host={} status={}", frame.getHost(), ex.getStatusCode().value());
                }
            }
        }

        List<String> parts = new ArrayList<>();
        if (!heading.isBlank()) parts.add(heading);
        String body = text.toString().strip();
        if (!body.isBlank()) parts.add(body);
        int read = 0;
        if (body.length() < ENOUGH_TEXT && !images.isEmpty()) {
            for (String imageText : readImages(session, images, progress)) {
                parts.add(imageText);
                read++;
            }
        }
        if (body.isBlank() && read == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "페이지에서 공고 내용을 찾지 못했어요. 로그인이 필요하거나 자동으로 읽을 수 없는 페이지일 수 있어요. "
                            + "공고 화면을 캡처해 사진·PDF 첨부에 붙여넣어 주세요.");
        }
        return JobTextExtractionService.finish(String.join("\n\n", parts), read > 0 ? "URL_OCR" : "URL_HTML",
                Math.max(1, read));
    }

    /** Reads content images in page order, skipping small or decorative ones and those without text. */
    private List<String> readImages(WebPageFetcher.Session session, List<URI> addresses, Consumer<Progress> progress) {
        List<String> texts = new ArrayList<>();
        var unique = new LinkedHashSet<>(addresses);
        int total = Math.min(unique.size(), MAX_IMAGES * 2);
        int tried = 0;
        progress.accept(new Progress("IMAGES", 0, total));
        for (URI address : unique) {
            if (texts.size() >= MAX_IMAGES || ++tried > MAX_IMAGES * 2) break;
            try {
                var image = session.get(address, JobTextExtractionService.MAX_BYTES, ACCEPT_IMAGE);
                String suffix = fileSuffix(image.contentType(), image.body());
                if (suffix == null || suffix.equals("pdf") || small(image.body())) continue;
                String text = files.recognizeImage(image.body(), suffix).strip();
                if (!text.isBlank()) texts.add(text);
            } catch (ResponseStatusException ex) {
                // An image that cannot be fetched or holds no text is skipped; an OCR outage stops the whole read.
                if (ex.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()) throw ex;
                log.info("url_image_skipped host={} status={}", address.getHost(), ex.getStatusCode().value());
            } finally {
                progress.accept(new Progress("IMAGES", tried, total));
            }
        }
        return texts;
    }

    private static Document parse(WebPageFetcher.Page page) {
        try {
            // A charset from the response header wins; otherwise jsoup reads the page's meta declaration (often EUC-KR).
            return Jsoup.parse(new ByteArrayInputStream(page.body()),
                    page.charset() == null ? null : page.charset().name(), page.uri().toString());
        } catch (IOException ex) {
            throw unreadable();
        }
    }

    /** Title, site name and a named logo: where a company name usually is when the posting body is an image. */
    static String heading(Document document) {
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        String title = document.title().strip();
        if (!title.isBlank()) lines.add("페이지 제목: " + title);
        String ogTitle = meta(document, "og:title");
        if (!ogTitle.isBlank() && !title.contains(ogTitle)) lines.add("공고 제목: " + ogTitle);
        String site = meta(document, "og:site_name");
        if (!site.isBlank() && !title.contains(site)) lines.add("사이트: " + site);
        for (Element logo : document.select("img[alt]")) {
            String hint = (logo.attr("src") + " " + logo.className() + " " + logo.id()).toLowerCase(Locale.ROOT);
            String alt = logo.attr("alt").strip();
            if (hint.contains("logo") && alt.length() > 1 && !alt.matches("(?i)로고|logo|.*로고 이미지")
                    && !title.contains(alt)) {
                lines.add("로고: " + alt);
                break;
            }
        }
        return String.join("\n", lines);
    }

    private static String meta(Document document, String property) {
        Element element = document.selectFirst("meta[property=" + property + "], meta[name=" + property + "]");
        return element == null ? "" : element.attr("content").strip();
    }

    /** Drops what is never posting content, then prefers a main or article element when it has real text. */
    static Element content(Document document) {
        document.select("script, style, noscript, template, svg, nav, header, footer, aside, form, button, select, "
                + "[hidden], [role=navigation], [role=banner], [role=contentinfo]").remove();
        Element main = document.selectFirst("main, article, [role=main]");
        if (main != null && main.text().length() >= 200) return main;
        return document.body() == null ? document : document.body();
    }

    /** Visible text with a line break around each block, so lists and table rows stay on their own lines. */
    static String blockText(Element root) {
        StringBuilder out = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) out.append(text.text());
                else if (node instanceof Element element && (element.isBlock() || element.normalName().equals("br"))) out.append('\n');
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element) {
                    if (element.isBlock()) out.append('\n');
                    else if (element.normalName().equals("td") || element.normalName().equals("th")) out.append(' ');
                }
            }
        }, root);
        // Each block on its own line; the empty lines that nested blocks leave behind are dropped.
        StringBuilder lines = new StringBuilder();
        for (String line : out.toString().split("\n")) {
            String clean = line.replace('\u00a0', ' ').strip().replaceAll("\\s{2,}", " ");
            if (!clean.isEmpty()) lines.append(clean).append('\n');
        }
        return lines.toString().strip();
    }

    /** Image addresses in page order. Lazy-loading pages keep the real address in a data attribute or srcset. */
    static List<URI> imageAddresses(Element root) {
        List<URI> addresses = new ArrayList<>();
        for (Element image : root.select("img")) {
            String hint = (image.attr("src") + " " + image.className() + " " + image.id() + " " + image.attr("alt"))
                    .toLowerCase(Locale.ROOT);
            if (DECORATION.matcher(hint).find() || declaredTooSmall(image)) continue;
            String source = "";
            for (String attribute : List.of("data-src", "data-original", "data-lazy-src", "data-url", "src")) {
                source = image.absUrl(attribute);
                if (!source.isBlank()) break;
            }
            if (source.isBlank() && image.hasAttr("srcset")) source = largestSrcset(image);
            if (source.isBlank() || source.startsWith("data:")) continue;
            String path = source.toLowerCase(Locale.ROOT);
            if (path.contains(".svg") || path.contains(".gif")) continue;
            try {
                addresses.add(WebPageFetcher.parse(source));
            } catch (ResponseStatusException ignored) {
                // A malformed address is skipped like a missing image.
            }
        }
        return addresses;
    }

    private static String largestSrcset(Element image) {
        String[] candidates = image.attr("srcset").split(",");
        String last = candidates[candidates.length - 1].strip();
        int space = last.indexOf(' ');
        String relative = space < 0 ? last : last.substring(0, space);
        try {
            return URI.create(image.baseUri()).resolve(WebPageFetcher.parse(relative)).toString();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static boolean declaredTooSmall(Element image) {
        return number(image.attr("width")) > 0 && number(image.attr("width")) < MIN_IMAGE_WIDTH
                || number(image.attr("height")) > 0 && number(image.attr("height")) < MIN_IMAGE_HEIGHT;
    }

    private static int number(String value) {
        try { return Integer.parseInt(value.strip().replace("px", "")); }
        catch (NumberFormatException ex) { return -1; }
    }

    private static List<URI> frameAddresses(Element root, URI page) {
        List<URI> frames = new ArrayList<>();
        for (Element frame : root.select("iframe[src], frame[src]")) {
            if (frames.size() >= MAX_FRAMES) break;
            try {
                URI address = WebPageFetcher.parse(frame.absUrl("src"));
                if (page.getHost().equalsIgnoreCase(address.getHost())) frames.add(address);
            } catch (ResponseStatusException ignored) {
                // Frames without a usable address are skipped.
            }
        }
        return frames;
    }

    /** Small PNG/JPEG images are icons or buttons. WebP has no JDK reader, so its size is left to the OCR service. */
    private static boolean small(byte[] bytes) {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return false;
            var reader = readers.next();
            try {
                reader.setInput(stream);
                return reader.getWidth(0) < MIN_IMAGE_WIDTH || reader.getHeight(0) < MIN_IMAGE_HEIGHT;
            } finally { reader.dispose(); }
        } catch (IOException ex) {
            return false;
        }
    }

    /** The file kind of a PDF or image response, by declared type and then by its first bytes; null for anything else. */
    private static String fileSuffix(String contentType, byte[] body) {
        switch (contentType) {
            case "application/pdf": return "pdf";
            case "image/png": return "png";
            case "image/jpeg", "image/jpg": return "jpg";
            case "image/webp": return "webp";
            default: break;
        }
        if (body.length >= 5 && body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F' && body[4] == '-') return "pdf";
        if (body.length >= 8 && (body[0] & 0xff) == 137 && body[1] == 'P' && body[2] == 'N' && body[3] == 'G') return "png";
        if (body.length >= 3 && (body[0] & 0xff) == 255 && (body[1] & 0xff) == 216 && (body[2] & 0xff) == 255) return "jpg";
        if (body.length >= 12 && body[0] == 'R' && body[1] == 'I' && body[2] == 'F' && body[3] == 'F'
                && body[8] == 'W' && body[9] == 'E' && body[10] == 'B' && body[11] == 'P') return "webp";
        return null;
    }

    private static ResponseStatusException unreadable() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                "이 주소의 내용은 공고로 읽을 수 없습니다. 공고 페이지 주소인지 확인해 주세요.");
    }
}
