package com.doggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.donggeon.jobrecommendation.extraction.OcrRunner;

class OcrRunnerTests {
    @Test
    void realTesseractRecognizesPrintedPostingWhenInstalled() throws Exception {
        boolean installed;
        try {
            var process = new ProcessBuilder("tesseract", "--list-langs").redirectErrorStream(true).start();
            installed = process.waitFor() == 0
                    && new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("kor");
        } catch (java.io.IOException ex) { installed = false; }
        if (Boolean.parseBoolean(System.getenv("ONFIT_REQUIRE_OCR"))) {
            assertThat(installed).as("Required Korean OCR engine must be installed").isTrue();
        }
        Assumptions.assumeTrue(installed, "Local Tesseract with Korean traineddata is not installed");

        var image = new BufferedImage(1100, 230, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 1100, 230);
            graphics.setColor(Color.BLACK);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(new Font("DejaVu Sans", Font.PLAIN, 56));
            graphics.drawString("Java Spring Boot Developer", 30, 90);
            graphics.setFont(new Font("NanumGothic", Font.PLAIN, 56));
            graphics.drawString("개발자 채용", 30, 180);
        } finally { graphics.dispose(); }
        byte[] png;
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            png = output.toByteArray();
        }
        assertThat(new OcrRunner(true, "tesseract").recognize(png, ".png"))
                .containsIgnoringCase("Java").containsIgnoringCase("Spring").contains("채용");
    }
}
