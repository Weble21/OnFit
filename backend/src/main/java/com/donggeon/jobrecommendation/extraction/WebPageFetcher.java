package com.donggeon.jobrecommendation.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fetches pages and images from the address a user gave. Redirects are followed by hand so every hop
 * passes {@link #checkTarget}, cookies set along the way are sent back (some posting sites redirect to
 * themselves until a session cookie is present), and each body is capped while it is read.
 */
@Component
public class WebPageFetcher {
    private static final Logger log = LoggerFactory.getLogger(WebPageFetcher.class);
    static final int MAX_REDIRECTS = 5;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    // Posting sites often refuse clients that do not look like a browser (decided 2026-10-20).
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    /** {@code contentType} is the media type without parameters; {@code charset} is null when the server sent none. */
    public record Page(URI uri, String contentType, Charset charset, byte[] body) {}

    /** One visit to a site: one cookie jar and one deadline for the page and everything read from it. */
    public Session open(Duration budget) {
        return new Session(Instant.now().plus(budget));
    }

    public static final class Session implements AutoCloseable {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
        private final Instant deadline;

        private Session(Instant deadline) { this.deadline = deadline; }

        public Page get(URI uri, int maxBytes, String accept) {
            URI current = uri;
            for (int hop = 0; ; hop++) {
                checkTarget(current);
                Duration left = Duration.between(Instant.now(), deadline);
                if (left.isNegative() || left.isZero()) throw failed("페이지를 가져오는 시간이 초과되었습니다.");
                var request = HttpRequest.newBuilder(current)
                        .timeout(left.compareTo(REQUEST_TIMEOUT) < 0 ? left : REQUEST_TIMEOUT)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", accept)
                        .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
                        .GET().build();
                HttpResponse<InputStream> response = send(request);
                int status = response.statusCode();
                if (REDIRECTS.contains(status)) {
                    close(response);
                    if (hop >= MAX_REDIRECTS) throw failed("페이지가 너무 여러 번 다른 주소로 이동합니다.");
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> failed("페이지를 가져오지 못했습니다."));
                    current = current.resolve(parse(location));
                    continue;
                }
                if (status != 200) {
                    close(response);
                    log.info("url_fetch_failed host={} status={}", current.getHost(), status);
                    throw failed(status == 401 || status == 403
                            ? "로그인이 필요하거나 외부 접근을 막은 페이지입니다."
                            : "페이지를 열 수 없습니다. 주소를 확인해 주세요.");
                }
                String type = response.headers().firstValue("Content-Type").orElse("");
                return new Page(current, mediaType(type), charset(type), read(response, maxBytes));
            }
        }

        private HttpResponse<InputStream> send(HttpRequest request) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException ex) {
                throw failed("페이지가 응답하지 않습니다. 잠시 후 다시 시도해 주세요.");
            } catch (IOException ex) {
                log.info("url_fetch_failed host={} type={}", request.uri().getHost(), ex.getClass().getSimpleName());
                throw failed("페이지에 연결하지 못했습니다. 주소를 확인해 주세요.");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw failed("페이지 가져오기가 중단되었습니다.");
            }
        }

        private static byte[] read(HttpResponse<InputStream> response, int maxBytes) {
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            try (InputStream body = response.body()) {
                if (declared > maxBytes) throw tooLarge();
                byte[] bytes = body.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) throw tooLarge();
                return bytes;
            } catch (IOException ex) {
                throw failed("페이지를 끝까지 읽지 못했습니다. 다시 시도해 주세요.");
            }
        }

        private static void close(HttpResponse<InputStream> response) {
            try { response.body().close(); } catch (IOException ignored) { /* Nothing left to read. */ }
        }

        @Override
        public void close() {
            client.close();
        }
    }

    /**
     * Parses an address as typed or as sent in a Location header. Characters a URI cannot hold as they are
     * (spaces, Korean) are percent-encoded; existing escapes are kept. The fragment never reaches the server.
     */
    public static URI parse(String raw) {
        StringBuilder out = new StringBuilder();
        for (byte b : raw.strip().getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c <= 0x20 || c >= 0x7f || "\"<>\\^`{|}".indexOf(c) >= 0) out.append('%').append(String.format("%02X", c));
            else out.append((char) c);
        }
        try {
            URI uri = URI.create(out.toString());
            return uri.getRawFragment() == null ? uri
                    : URI.create(out.substring(0, out.indexOf("#")));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 주소가 아닙니다.");
        }
    }

    /**
     * Every address, including each redirect target and image, passes here before it is requested.
     * Before deployment this must also refuse private, loopback, link-local and internal service addresses
     * and pin the connection to the checked address (decided 2026-10-20 to add then, not now).
     */
    static void checkTarget(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || !Set.of("http", "https").contains(scheme) || uri.getHost() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "http 또는 https로 시작하는 공고 주소를 입력해 주세요.");
        }
    }

    private static String mediaType(String header) {
        int end = header.indexOf(';');
        return (end < 0 ? header : header.substring(0, end)).strip().toLowerCase(Locale.ROOT);
    }

    private static Charset charset(String header) {
        for (String part : header.split(";")) {
            String value = part.strip();
            if (value.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try {
                    return Charset.forName(value.substring(8).replace("\"", "").strip());
                } catch (RuntimeException ignored) {
                    return null; // An unknown name is ignored; the page's own declaration decides.
                }
            }
        }
        return null;
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "페이지나 이미지가 너무 커서 가져올 수 없습니다.");
    }

    private static ResponseStatusException failed(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }
}
