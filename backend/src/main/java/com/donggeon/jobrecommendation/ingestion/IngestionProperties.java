package com.donggeon.jobrecommendation.ingestion;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "onfit.ingestion")
public class IngestionProperties {
    private boolean enabled;
    private Map<String, SourcePolicy> sources = Map.of();
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, SourcePolicy> getSources() { return sources; }
    public void setSources(Map<String, SourcePolicy> sources) { this.sources = sources; }
    public record SourcePolicy(String allowedHost, String termsUrl, LocalDate reviewedOn,
                               String permissionEvidence) { }
}
