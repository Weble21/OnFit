package com.donggeon.jobrecommendation.extraction;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Sends one image to the private PaddleOCR service (ocr-service/) and returns its text.
 * The image travels only in the request body; neither side stores or logs it.
 */
@Component
public class PaddleOcrClient {
    private static final Logger log = LoggerFactory.getLogger(PaddleOcrClient.class);
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            ".png", "image/png", ".jpg", "image/jpeg", ".jpeg", "image/jpeg", ".webp", "image/webp");
    private static final int MAX_TEXT = 1_000_000;

    record OcrResponse(String text) {}

    private final URI endpoint;
    private final String token;
    private final Duration timeout;
    private final JsonMapper json;
    private final HttpClient client;

    public PaddleOcrClient(@Value("${onfit.extraction.paddle-url:http://127.0.0.1:8002/v1/ocr}") String url,
                           @Value("${onfit.extraction.paddle-token:}") String token,
                           @Value("${onfit.extraction.paddle-timeout-ms:60000}") int timeoutMs,
                           JsonMapper json) {
        // Long postings are split into several tiles, each read on the CPU, so this is longer than the AI timeout.
        if (timeoutMs < 1000 || timeoutMs > 300_000) throw new IllegalArgumentException("OCR timeout must be 1000..300000 ms");
        this.endpoint = URI.create(url);
        if (!Set.of("http", "https").contains(endpoint.getScheme())) throw new IllegalArgumentException("OCR URL must be HTTP(S)");
        this.token = token;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String recognize(byte[] image, String suffix) {
        String type = CONTENT_TYPES.get(suffix);
        if (type == null) throw new IllegalArgumentException("Unsupported OCR image type");
        if (token.isBlank()) {
            log.warn("ocr_unavailable reason=missing_token");
            throw unavailable();
        }
        HttpResponse<String> response;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", type).header("X-OnFit-OCR-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(image)).build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException ex) {
            log.warn("ocr_unavailable reason=timeout");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OCR 처리 시간이 초과되었습니다. 더 선명하거나 짧은 파일을 사용해 주세요.");
        } catch (IOException ex) {
            log.warn("ocr_unavailable reason=connection type={}", ex.getClass().getSimpleName());
            throw unavailable();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OCR 처리가 중단되었습니다.");
        }
        int status = response.statusCode();
        if (status == 422 || status == 413 || status == 415) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "이미지에서 텍스트를 읽지 못했습니다. 파일을 확인해 주세요.");
        if (status != 200) {
            log.warn("ocr_unavailable reason=status status={}", status);
            throw unavailable();
        }
        OcrResponse parsed;
        try {
            parsed = json.readValue(response.body(), OcrResponse.class);
        } catch (RuntimeException ex) {
            log.warn("ocr_unavailable reason=invalid_response");
            throw unavailable();
        }
        if (parsed == null || parsed.text() == null) throw unavailable();
        if (parsed.text().length() > MAX_TEXT) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "추출된 텍스트가 너무 깁니다.");
        return parsed.text();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "OCR 서버를 사용할 수 없습니다. 잠시 후 다시 시도하거나 원본을 보며 텍스트를 입력해 주세요.");
    }
}
