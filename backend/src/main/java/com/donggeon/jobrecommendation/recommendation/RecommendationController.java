package com.doggeon.jobrecommendation.recommendation;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    /** 201 when at least one new snapshot was stored, 200 when every result was reused. */
    @PostMapping
    public ResponseEntity<List<RecommendationResponse>> create() {
        RecommendationService.Result result = service.create();
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.recommendations());
    }

    @GetMapping("/{recommendationId}")
    public RecommendationResponse get(@PathVariable Long recommendationId) {
        return service.get(recommendationId);
    }
}
