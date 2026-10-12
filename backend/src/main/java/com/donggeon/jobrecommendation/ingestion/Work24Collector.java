package com.donggeon.jobrecommendation.ingestion;

import com.donggeon.jobrecommendation.domain.JobPostingStatus;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "onfit.ingestion.work24.enabled", havingValue = "true")
public class Work24Collector {
    private static final Logger log = LoggerFactory.getLogger(Work24Collector.class);
    private static final String API = "https://www.work24.go.kr/cm/openApi/call/wk/";
    private static final String SOURCE = "work24";
    private final JobImportService importer;
    private final IngestionProperties policies;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String key;
    private final String keyword;
    private final int maxPages;
    private final String alertUrl;
    private volatile Instant lastSuccess;
    private volatile Instant lastFailure;
    private volatile int consecutiveFailures;
    private volatile String lastError;
    private volatile boolean partialCoverage;

    public Work24Collector(JobImportService importer, IngestionProperties policies, JsonMapper json, Clock clock,
            @Value("${onfit.ingestion.work24.auth-key:}") String key,
            @Value("${onfit.ingestion.work24.keyword:개발자}") String keyword,
            @Value("${onfit.ingestion.work24.max-pages:2}") int maxPages,
            @Value("${onfit.ingestion.work24.alert-url:}") String alertUrl) {
        this.importer = importer;
        this.policies = policies;
        this.json = json;
        this.clock = clock;
        this.key = key;
        this.keyword = keyword;
        this.maxPages = maxPages;
        this.alertUrl = alertUrl;
        if (!policies.isEnabled() || key.isBlank() || keyword.isBlank() || maxPages < 1 || maxPages > 10
                || policies.getSources().get(SOURCE) == null
                || policies.getSources().get(SOURCE).permissionEvidence() == null
                || policies.getSources().get(SOURCE).permissionEvidence().isBlank()) {
            throw new IllegalStateException("Work24 collection requires an approved source, key and bounded pages");
        }
        if (!alertUrl.isBlank() && !"https".equalsIgnoreCase(URI.create(alertUrl).getScheme())) {
            throw new IllegalStateException("Collection alert URL must use HTTPS");
        }
    }

    @Scheduled(cron = "${onfit.ingestion.work24.cron:0 0 2,14 * * *}", zone = "UTC")
    public void collect() {
        try {
            CollectionResult result = collectOnce();
            int expired = importer.expireDue(SOURCE);
            boolean recovered = consecutiveFailures > 0;
            boolean coverageRestored = partialCoverage && !result.truncated();
            lastSuccess = clock.instant();
            consecutiveFailures = 0;
            lastError = null;
            if (result.truncated() && !partialCoverage) alert("partial", "Result exceeds configured page limit");
            partialCoverage = result.truncated();
            log.info("work24_collection_success imported={} expired={} partial={}",
                    result.imported(), expired, partialCoverage);
            if (recovered) alert("recovered", "Collection resumed");
            if (coverageRestored) alert("coverage_restored", "All result pages were read");
        } catch (Exception failure) {
            lastFailure = clock.instant();
            consecutiveFailures++;
            lastError = failure.getClass().getSimpleName();
            log.error("work24_collection_failure count={} type={}", consecutiveFailures, lastError);
            if (consecutiveFailures == 1) alert("failed", lastError);
        }
    }

    @Scheduled(cron = "0 30 0 * * *", zone = "UTC")
    public void expireDeadlines() {
        try {
            log.info("work24_deadline_expiry expired={}", importer.expireDue(SOURCE));
        } catch (Exception failure) {
            log.error("work24_deadline_expiry_failure type={}", failure.getClass().getSimpleName());
            alert("expiry_failed", failure.getClass().getSimpleName());
        }
    }

    public Map<String, Object> status() {
        return Map.of("source", SOURCE, "lastSuccess", lastSuccess == null ? "" : lastSuccess.toString(),
                "lastFailure", lastFailure == null ? "" : lastFailure.toString(),
                "consecutiveFailures", consecutiveFailures, "partialCoverage", partialCoverage,
                "lastError", lastError == null ? "" : lastError);
    }

    private record CollectionResult(int imported, boolean truncated) { }

    private CollectionResult collectOnce() throws Exception {
        int imported = 0;
        boolean truncated = false;
        for (int page = 1; page <= maxPages; page++) {
            String listUrl = API + "callOpenApiSvcInfo210L01.do?authKey=" + enc(key)
                    + "&callTp=L&returnType=XML&startPage=" + page + "&display=30&regDate=D-3&keyword=" + enc(keyword);
            Element root = parse(fetch(listUrl), "wantedRoot");
            int total = Integer.parseInt(value(root, "total"));
            if (total > maxPages * 30) truncated = true;
            NodeList wanted = root.getElementsByTagName("wanted");
            if (total > 0 && wanted.getLength() == 0) throw new IllegalStateException("Empty Work24 page");
            for (int index = 0; index < wanted.getLength(); index++) {
                Element item = (Element) wanted.item(index);
                String id = value(item, "wantedAuthNo");
                if (id.isBlank() || !id.matches("[A-Za-z0-9-]{1,80}")) {
                    throw new IllegalStateException("Invalid Work24 posting ID");
                }
                String detailUrl = API + "callOpenApiSvcInfo210D01.do?authKey=" + enc(key)
                        + "&callTp=D&returnType=XML&wantedAuthNo=" + enc(id) + "&infoSvc=VALIDATION";
                Element detail = parse(fetch(detailUrl), "wantedDtl");
                JobImportRequest request = map(item, detail, clock.instant());
                importer.ingest(request);
                imported++;
            }
            if (page * 30 >= total) break;
        }
        return new CollectionResult(imported, truncated);
    }

    private String fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body().length() > 2_000_000) {
            throw new IllegalStateException("Work24 HTTP status or response size invalid: " + response.statusCode());
        }
        return response.body();
    }

    public static JobImportRequest map(Element item, Element detail, Instant observedAt) {
        String id = value(item, "wantedAuthNo");
        if (!id.equals(value(detail, "wantedAuthNo"))) throw new IllegalStateException("Work24 detail ID mismatch");
        Element company = child(detail, "corpInfo");
        Element info = child(detail, "wantedInfo");
        String title = required(value(info, "wantedTitle"), "title");
        String duties = required(value(info, "jobCont"), "job content");
        String role = fallback(value(info, "jobsNm"), title);
        String location = fallback(value(info, "workRegion"), value(item, "region"));
        LocalDate deadline = date(value(info, "receiptCloseDt"));
        if (deadline == null) deadline = date(value(item, "closeDt"));
        JobImportRequest.Content content = new JobImportRequest.Content(
                required(value(company, "corpNm"), "company"), title, limit(role, 120),
                optional(value(company, "indTpCdNm"), 100), optional(value(company, "busiSize"), 100),
                optional(value(info, "enterTpNm"), 100), limit(duties, 30000), limit(duties, 30000),
                required(limit(location, 200), "location"), deadline, JobPostingStatus.OPEN,
                List.of(), List.of());
        String url = "https://www.work24.go.kr/wk/a/b/1500/empDetailAuthView.do?wantedAuthNo="
                + enc(id) + "&infoTypeCd=VALIDATION&infoTypeGroup=tb_workinfoworknet";
        return new JobImportRequest(SOURCE, id, url, observedAt, content);
    }

    public static Element parse(String xml, String expectedRoot) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Element root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
        if (!expectedRoot.equals(root.getTagName())) throw new IllegalStateException("Unexpected Work24 XML root");
        return root;
    }

    private static Element child(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        if (nodes.getLength() == 0) throw new IllegalStateException("Missing Work24 " + name);
        return (Element) nodes.item(0);
    }
    private static String value(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
    }
    private static String required(String value, String name) {
        if (value.isBlank()) throw new IllegalStateException("Missing Work24 " + name);
        return value;
    }
    private static String fallback(String first, String second) { return first.isBlank() ? second : first; }
    private static String limit(String value, int max) { return value.length() > max ? value.substring(0, max) : value; }
    private static String optional(String value, int max) { return value.isBlank() ? null : limit(value, max); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static LocalDate date(String value) {
        if (value.isBlank() || !value.matches("\\d{4}[-.]?\\d{2}[-.]?\\d{2}")) return null;
        try { return LocalDate.parse(value.replace("-", "").replace(".", ""), DateTimeFormatter.BASIC_ISO_DATE); }
        catch (DateTimeParseException invalid) { throw new IllegalStateException("Invalid Work24 deadline"); }
    }

    private void alert(String state, String detail) {
        if (alertUrl.isBlank()) return;
        try {
            String payload = json.writeValueAsString(Map.of("source", SOURCE, "state", state,
                    "at", clock.instant().toString(), "detail", detail));
            HttpRequest request = HttpRequest.newBuilder(URI.create(alertUrl)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) log.error("work24_alert_failure status={}", status);
        } catch (Exception ignored) {
            log.error("work24_alert_failure type={}", ignored.getClass().getSimpleName());
        }
    }
}
