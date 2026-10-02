package com.doggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.doggeon.jobrecommendation.extraction.OcrRunner;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class OcrRunnerTests {
    @Test
    void realTesseractRecognizesPrintedPostingWhenInstalled() throws Exception {
        boolean installed;
        try {
            var process = new ProcessBuilder("tesseract", "--list-langs").redirectErrorStream(true).start();
            installed = process.waitFor() == 0
                    && new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("kor");
        } catch (java.io.IOException ex) { installed = false; }
        Assumptions.assumeTrue(installed, "Local Tesseract with Korean traineddata is not installed");

        var image = new BufferedImage(1100, 150, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 1100, 150);
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font("SansSerif", Font.PLAIN, 56));
            graphics.drawString("Java Spring Boot Developer", 30, 100);
        } finally { graphics.dispose(); }
        byte[] png;
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            png = output.toByteArray();
        }
        assertThat(new OcrRunner(true, "tesseract").recognize(png, ".png"))
                .containsIgnoringCase("Java").containsIgnoringCase("Spring");
    }
}
