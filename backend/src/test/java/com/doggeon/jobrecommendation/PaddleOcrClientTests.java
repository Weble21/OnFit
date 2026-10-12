package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.donggeon.jobrecommendation.extraction.PaddleOcrClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class PaddleOcrClientTests {
    private HttpServer server;
    private final AtomicReference<String> seenType = new AtomicReference<>();
    private final AtomicReference<String> seenToken = new AtomicReference<>();
    private final AtomicReference<byte[]> seenBody = new AtomicReference<>();

    private String serve(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/ocr", exchange -> {
            seenType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            seenToken.set(exchange.getRequestHeaders().getFirst("X-OnFit-OCR-Token"));
            seenBody.set(exchange.getRequestBody().readAllBytes());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/ocr";
    }

    private static PaddleOcrClient client(String url, String token) {
        return new PaddleOcrClient(url, token, 5000, JsonMapper.builder().build());
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void sendsImageWithTypeAndTokenAndReturnsText() throws Exception {
        String url = serve(200, "{\"text\":\"백엔드 개발자 채용\\nJava Spring Boot\",\"lines\":2,\"tiles\":1}");
        assertThat(client(url, "secret").recognize(new byte[] {1, 2, 3}, ".jpg")).isEqualTo("백엔드 개발자 채용\nJava Spring Boot");
        assertThat(seenType.get()).isEqualTo("image/jpeg");
        assertThat(seenToken.get()).isEqualTo("secret");
        assertThat(seenBody.get()).containsExactly(1, 2, 3);
    }

    @Test void unreadableImageIsUnprocessableAndServiceFailuresAreUnavailable() throws Exception {
        String url = serve(422, "{\"detail\":\"No text\"}");
        assertThatThrownBy(() -> client(url, "secret").recognize(new byte[] {1}, ".png"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        stop();
        String failing = serve(500, "{}");
        assertThatThrownBy(() -> client(failing, "secret").recognize(new byte[] {1}, ".png"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    /** Runs against a real ocr-service when ONFIT_OCR_TEST_URL and ONFIT_OCR_TEST_TOKEN are set. */
    @Test void realServiceReadsKoreanPostingWhenConfigured() throws Exception {
        String url = System.getenv("ONFIT_OCR_TEST_URL");
        String token = System.getenv("ONFIT_OCR_TEST_TOKEN");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null && token != null, "No OCR service configured");
        var image = new java.awt.image.BufferedImage(900, 260, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, 900, 260);
            graphics.setColor(java.awt.Color.BLACK);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 44));
            graphics.drawString("Java Spring Boot Developer", 30, 90);
            graphics.drawString("백엔드 개발자 채용", 30, 190);
        } finally { graphics.dispose(); }
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);
        assertThat(client(url, token).recognize(png.toByteArray(), ".png"))
                .containsIgnoringCase("Spring").contains("개발자").contains("채용");
    }

    @Test void missingTokenOrUnreachableServiceIsUnavailableWithoutSendingTheImage() throws Exception {
        String url = serve(200, "{\"text\":\"x\"}");
        assertThatThrownBy(() -> client(url, " ").recognize(new byte[] {1}, ".png"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(seenBody.get()).isNull();
        stop();
        assertThatThrownBy(() -> client(url, "secret").recognize(new byte[] {1}, ".png"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
