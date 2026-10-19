package com.donggeon.jobrecommendation.jobanalysis;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/job-analysis")
public class JobAnalysisController {
    private final JobAnalysisService service;

    public JobAnalysisController(JobAnalysisService service) { this.service = service; }

    /** Returns extracted text for review. Uploading never scores anything by itself. */
    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JobAnalysisResponse.Draft extract(@RequestParam("file") MultipartFile file) {
        return service.createDraft(file);
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
