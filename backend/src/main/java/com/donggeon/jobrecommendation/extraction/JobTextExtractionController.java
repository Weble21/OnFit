package com.doggeon.jobrecommendation.extraction;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/job-text")
public class JobTextExtractionController {
    private final JobTextExtractionService service;

    public JobTextExtractionController(JobTextExtractionService service) { this.service = service; }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JobTextExtractionService.Result extract(@RequestParam("file") MultipartFile file) {
        return service.extract(file);
    }
}
