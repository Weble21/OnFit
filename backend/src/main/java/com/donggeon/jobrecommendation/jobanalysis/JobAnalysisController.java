package com.donggeon.jobrecommendation.jobanalysis;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.donggeon.jobrecommendation.extraction.JobUrlExtractionService;

import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/job-analysis")
public class JobAnalysisController {
    private static final Logger log = LoggerFactory.getLogger(JobAnalysisController.class);
    private static final String NDJSON = "application/x-ndjson";
    private final JobAnalysisService service;
    private final JsonMapper json;

    public JobAnalysisController(JobAnalysisService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    /** Returns extracted text for review. Uploading never scores anything by itself. */
    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JobAnalysisResponse.Draft extract(@RequestParam("file") MultipartFile file) {
        return service.createDraft(file);
    }

    /**
     * Fetches the posting at an address and returns its text for review, like {@link #extract}. Reading a page of
     * images takes a while, so the answer is streamed as JSON lines: {"type":"progress",stage,done,total} while
     * working, then one {"type":"draft",draft} or {"type":"error",status,detail}. A bad address is refused with a
     * plain HTTP error before streaming starts.
     */
    @PostMapping(value = "/url", produces = NDJSON)
    public void extractUrl(@Valid @RequestBody JobAnalysisResponse.UrlRequest request, HttpServletResponse response)
            throws IOException {
        JobUrlExtractionService.target(request.url());
        response.setContentType(NDJSON + ";charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        OutputStream out = response.getOutputStream();
        try {
            var draft = service.createDraftFromUrl(request.url(), progress -> send(out, Map.of("type", "progress",
                    "stage", progress.stage(), "done", progress.done(), "total", progress.total())));
            send(out, Map.of("type", "draft", "draft", draft));
        } catch (UncheckedIOException closed) {
            // The browser went away (the user cancelled or changed the address); nothing is left to tell it.
        } catch (ResponseStatusException ex) {
            sendLast(out, Map.of("type", "error", "status", ex.getStatusCode().value(),
                    "detail", ex.getReason() == null ? "공고를 가져오지 못했습니다." : ex.getReason()));
        } catch (RuntimeException ex) {
            log.warn("url_extract_failed type={}", ex.getClass().getSimpleName());
            sendLast(out, Map.of("type", "error", "status", 500, "detail", "공고를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요."));
        }
    }

    /** The closing error line; if the browser is already gone there is no one left to tell. */
    private void sendLast(OutputStream out, Map<String, ?> event) {
        try { send(out, event); } catch (UncheckedIOException ignored) { /* Connection closed. */ }
    }

    private void send(OutputStream out, Map<String, ?> event) {
        try {
            out.write(json.writeValueAsBytes(event));
            out.write('\n');
            out.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @PostMapping("/text")
    public JobAnalysisResponse analyze(@Valid @RequestBody JobAnalysisRequest request) {
        return service.analyze(request);
    }

    @GetMapping("/{analysisId}")
    public JobAnalysisResponse get(@PathVariable Long analysisId) {
        return service.get(analysisId);
    }

    @PostMapping("/{analysisId}/role-analysis")
    public JobAnalysisResponse retryRoleAnalysis(@PathVariable Long analysisId) {
        return service.retryRoleAnalysis(analysisId);
    }
}
