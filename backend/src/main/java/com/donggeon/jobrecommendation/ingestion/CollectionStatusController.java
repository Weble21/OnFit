package com.donggeon.jobrecommendation.ingestion;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/jobs/collection")
public class CollectionStatusController {
    private final ObjectProvider<Work24Collector> collector;

    public CollectionStatusController(ObjectProvider<Work24Collector> collector) {
        this.collector = collector;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Work24Collector active = collector.getIfAvailable();
        return active == null ? Map.of("source", "work24", "enabled", false)
                : Map.of("enabled", true, "run", active.status());
    }
}
