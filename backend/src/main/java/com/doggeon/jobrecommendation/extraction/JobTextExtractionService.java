package com.doggeon.jobrecommendation.extraction;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobTextExtractionService {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_TEXT = 15_000;
    private static final int MAX_PAGES = 10;
    private static final long MAX_PIXELS = 20_000_000;
    private final OcrRunner ocr;

    public record Result(String text, String method, int pages, boolean truncated) { }

    public JobTextExtractionService(OcrRunner ocr) { this.ocr = ocr; }

    public Result extract(MultipartFile file) {
        if (file == null || file.isEmpty()) throw bad("비어 있는 파일은 사용할 수 없습니다.");
        if (file.getSize() > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "10MB 이하의 파일을 선택해 주세요.");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String suffix = name.substring(Math.max(0, name.lastIndexOf('.') + 1));
        String mime = switch (suffix) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "JPG, PNG, WebP 이미지 또는 PDF만 사용할 수 있습니다.");
        };
        if (file.getContentType() != null && !file.getContentType().isBlank()
                && !file.getContentType().equals(mime) && !file.getContentType().equals("application/octet-stream")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "파일 이름과 형식이 맞지 않습니다.");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) throw bad("비어 있는 파일은 사용할 수 없습니다.");
            if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "10MB 이하의 파일을 선택해 주세요.");
            if (!signature(bytes, suffix)) throw bad("파일 내용과 형식이 맞지 않습니다.");
            if (suffix.equals("pdf")) return pdf(bytes);
            validateImageSize(bytes, suffix);
            return result(ocr.recognize(bytes, "." + suffix), "OCR", 1);
        } catch (IOException ex) {
            throw bad("파일을 읽지 못했습니다. 원본 파일을 확인해 주세요.");
        }
    }

    private Result pdf(byte[] bytes) {
        try (var document = Loader.loadPDF(bytes)) {
            int pages = document.getNumberOfPages();
            if (pages < 1 || pages > MAX_PAGES) throw bad("PDF는 1~10쪽만 사용할 수 있습니다.");
            var stripper = new PDFTextStripper();
            var renderer = new PDFRenderer(document);
            List<String> parts = new ArrayList<>();
            boolean usedOcr = false;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document).trim();
                if (pageText.length() < 20) {
                    var box = document.getPage(page - 1).getMediaBox();
                    if ((long) Math.ceil(box.getWidth() * 150 / 72) * (long) Math.ceil(box.getHeight() * 150 / 72)
                            > MAX_PIXELS) throw bad("PDF 페이지 크기가 너무 큽니다.");
                    BufferedImage image = renderer.renderImageWithDPI(page - 1, 150, ImageType.RGB);
                    try (var output = new ByteArrayOutputStream()) {
                        ImageIO.write(image, "png", output);
                        pageText = ocr.recognize(output.toByteArray(), ".png").trim();
                    } finally { image.flush(); }
                    usedOcr = true;
                }
                if (!pageText.isBlank()) parts.add(pageText);
            }
            return result(String.join("\n\n", parts), usedOcr ? "PDF_TEXT_AND_OCR" : "PDF_TEXT", pages);
        } catch (IOException ex) {
            throw bad("PDF를 읽지 못했습니다. 손상되거나 암호화된 파일인지 확인해 주세요.");
        }
    }

    private static Result result(String text, String method, int pages) {
        String cleaned = text.replace("\u0000", "").replace("\r\n", "\n").trim();
        if (cleaned.isBlank()) throw bad("파일에서 텍스트를 찾지 못했습니다. 직접 입력해 주세요.");
        boolean truncated = cleaned.length() > MAX_TEXT;
        return new Result(truncated ? cleaned.substring(0, MAX_TEXT) : cleaned, method, pages, truncated);
    }

    private static void validateImageSize(byte[] bytes, String suffix) throws IOException {
        if (suffix.equals("webp")) return; // JDK ImageIO lacks a built-in WebP reader; OCR validates decoding.
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw bad("이미지를 읽지 못했습니다.");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > MAX_PIXELS) throw bad("이미지 크기는 2천만 픽셀 이하만 사용할 수 있습니다.");
            } finally { reader.dispose(); }
        }
    }

    private static boolean signature(byte[] bytes, String suffix) {
        return switch (suffix) {
            case "pdf" -> starts(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "png" -> starts(bytes, new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            case "jpg", "jpeg" -> starts(bytes, new byte[] {(byte) 255, (byte) 216, (byte) 255});
            case "webp" -> bytes.length >= 12 && starts(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && Arrays.equals(Arrays.copyOfRange(bytes, 8, 12), "WEBP".getBytes(StandardCharsets.US_ASCII));
            default -> false;
        };
    }

    private static boolean starts(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(Arrays.copyOf(bytes, prefix.length), prefix);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
