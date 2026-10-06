package com.doggeon.jobrecommendation.ingestion;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/jobs")
public class JobImportController {
    private final JobImportService imports;
    public JobImportController(JobImportService imports) { this.imports = imports; }
    @PostMapping("/import")
    public ResponseEntity<JobImportService.Result> ingest(@Valid @RequestBody JobImportRequest request) {
        var result = imports.ingest(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }
    @GetMapping("/{jobId}/revisions")
    public List<JobImportService.Revision> revisions(@PathVariable long jobId) { return imports.revisions(jobId); }
}
