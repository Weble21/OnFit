package com.doggeon.jobrecommendation.extraction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Runs local Tesseract without retaining uploaded image bytes or OCR output. */
@Component
public class OcrRunner {
    private final boolean enabled;
    private final String command;

    public OcrRunner(@Value("${onfit.extraction.ocr-enabled:true}") boolean enabled,
                     @Value("${onfit.extraction.ocr-command:tesseract}") String command) {
        this.enabled = enabled;
        this.command = command;
    }

    public String recognize(byte[] image, String suffix) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "OCR이 설정되지 않았습니다. 원본을 보며 텍스트 입력 방식을 사용해 주세요.");
        Path input = null;
        Path output = null;
        Process process = null;
        try {
            input = Files.createTempFile("onfit-ocr-", suffix);
            output = Files.createTempFile("onfit-ocr-output-", ".txt");
            Files.write(input, image);
            process = new ProcessBuilder(command, input.toString(), "stdout", "-l", "kor+eng", "--psm", "6")
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "OCR 처리 시간이 초과되었습니다. 더 선명한 파일을 사용해 주세요.");
            }
            if (process.exitValue() != 0) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "이미지에서 텍스트를 읽지 못했습니다. 파일을 확인해 주세요.");
            if (Files.size(output) > 1_000_000) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "추출된 텍스트가 너무 깁니다.");
            return Files.readString(output, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OCR을 사용할 수 없습니다. 서버의 Tesseract와 한국어 언어 데이터를 확인해 주세요.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OCR 처리가 중단되었습니다.");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (input != null) try { Files.deleteIfExists(input); } catch (IOException ignored) { }
            if (output != null) try { Files.deleteIfExists(output); } catch (IOException ignored) { }
        }
    }
}
