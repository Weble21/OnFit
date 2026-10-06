package com.doggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import com.donggeon.jobrecommendation.extraction.JobTextExtractionService;
import com.donggeon.jobrecommendation.extraction.OcrRunner;

@SpringBootTest
@ActiveProfiles("test")
class JobTextExtractionTests {
    @Autowired WebApplicationContext context;
    @Autowired JobTextExtractionService service;
    @MockitoBean OcrRunner ocr;

    @Test
    void extractsPdfTextWithoutOcrAndRequiresClientReview() throws Exception {
        byte[] pdf = pdf("Job Posting Java Spring Boot PostgreSQL required");
        var file = new MockMultipartFile("file", "posting.pdf", "application/pdf", pdf);
        var result = service.extract(file);
        assertThat(result.text()).contains("Java Spring Boot PostgreSQL");
        assertThat(result.method()).isEqualTo("PDF_TEXT");
        assertThat(result.pages()).isEqualTo(1);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(multipart("/api/job-text/extract").file(file)).andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("PDF_TEXT"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("PostgreSQL")));
    }

    @Test
    void scansImageAndTextlessPdfWithoutInventingScores() throws Exception {
        when(ocr.recognize(any(byte[].class), eq(".png"))).thenReturn("Java Spring Boot PostgreSQL developer wanted");
        var image = new java.awt.image.BufferedImage(200, 100, java.awt.image.BufferedImage.TYPE_INT_RGB);
        byte[] png;
        try (var output = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", output);
            png = output.toByteArray();
        }
        var photo = service.extract(new MockMultipartFile("file", "posting.png", "image/png", png));
        assertThat(photo.method()).isEqualTo("OCR");
        assertThat(photo.text()).contains("PostgreSQL");
        assertThat(service.extract(new MockMultipartFile("file", "scan.pdf", "application/pdf", pdf(null)))
                .method()).isEqualTo("PDF_TEXT_AND_OCR");
        when(ocr.recognize(any(byte[].class), eq(".png"))).thenReturn("   ");
        assertThatThrownBy(() -> service.extract(new MockMultipartFile("file", "scan.pdf", "application/pdf", pdf(null))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("텍스트를 찾지 못했습니다");
    }

    @Test
    void rejectsDisguisedOversizeEmptyAndUnusableFiles() throws Exception {
        assertThatThrownBy(() -> service.extract(new MockMultipartFile("file", "bad.pdf", "application/pdf",
                "not a PDF".getBytes(StandardCharsets.UTF_8)))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.extract(new MockMultipartFile("file", "bad.txt", "text/plain", "a".getBytes())))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.extract(new MockMultipartFile("file", "empty.png", "image/png", new byte[0])))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.extract(new MockMultipartFile("file", "large.png", "image/png",
                new byte[JobTextExtractionService.MAX_BYTES + 1]))).isInstanceOf(ResponseStatusException.class);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(multipart("/api/job-text/extract").file("file", "garbage".getBytes()))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/job-text/extract").file(new MockMultipartFile("file", "large.png",
                "image/png", new byte[JobTextExtractionService.MAX_BYTES + 1])))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").value("10MB 이하의 파일을 선택해 주세요."));
    }

    private static byte[] pdf(String text) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            if (text != null) {
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
