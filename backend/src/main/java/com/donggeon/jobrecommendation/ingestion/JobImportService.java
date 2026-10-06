package com.donggeon.jobrecommendation.ingestion;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.donggeon.jobrecommendation.domain.JobPosting;
import com.donggeon.jobrecommendation.domain.JobPostingStatus;
import com.donggeon.jobrecommendation.recommendation.JobResponse;
import com.donggeon.jobrecommendation.seed.JobPostingRepository;

import tools.jackson.databind.json.JsonMapper;

@Service
public class JobImportService {
    private final JobPostingRepository jobs;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final IngestionProperties policies;
    private final Clock clock;
    public JobImportService(JobPostingRepository jobs, JdbcTemplate jdbc, JsonMapper json,
                            IngestionProperties policies, Clock clock) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.json = json;
        this.policies = policies;
        this.clock = clock;
    }
    public record Result(JobResponse job, boolean created, boolean changed) { }

    @Transactional
    public Result ingest(JobImportRequest request) {
        requireEnabled();
        var policy = policies.getSources().get(request.sourceName());
        if (policy == null || policy.allowedHost() == null || policy.reviewedOn() == null
                || policy.reviewedOn().isAfter(LocalDate.now(clock)) || policy.termsUrl() == null
                || policy.termsUrl().isBlank() || policy.termsUrl().length() > 1000
                || policy.permissionEvidence() == null || policy.permissionEvidence().isBlank()
                || policy.permissionEvidence().length() > 2000) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "수집 허용 범위를 확인한 출처만 등록할 수 있습니다.");
        }
        URI source;
        try { source = URI.create(request.sourceUrl()); }
        catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "출처 URL을 확인해 주세요.");
        }
        if (!"https".equalsIgnoreCase(source.getScheme()) || source.getHost() == null
                || !source.getHost().equalsIgnoreCase(policy.allowedHost()) || source.getUserInfo() != null
                || (source.getPort() != -1 && source.getPort() != 443)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "승인된 출처의 HTTPS 원문 URL이 필요합니다.");
        }
        if (request.observedAt().isAfter(clock.instant().plusSeconds(300))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수집 시각이 현재보다 늦습니다.");
        }
        var existing = jobs.findBySourceNameAndExternalId(request.sourceName(), request.externalId());
        JobPosting job = existing.orElseGet(() -> JobPosting.imported(
                request.sourceName(), request.externalId(), request.observedAt()));
        if (job.getLastSeenAt() != null && request.observedAt().isBefore(job.getLastSeenAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "더 오래된 수집 결과는 반영할 수 없습니다.");
        }
        var content = request.content();
        JobPostingStatus status = content.status();
        if (status == JobPostingStatus.OPEN && content.deadline() != null
                && content.deadline().isBefore(LocalDate.now(clock))) status = JobPostingStatus.EXPIRED;
        String snapshot = json.writeValueAsString(new Snapshot(request.sourceUrl(), content, status));
        String hash = sha256(snapshot);
        boolean changed = !hash.equals(job.getContentHash());
        if (changed && request.observedAt().equals(job.getLastSeenAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 수집 시각의 서로 다른 원문은 반영할 수 없습니다.");
        }
        job.markSeen(request.observedAt());
        if (changed) {
            job.updateImported(request.sourceUrl(), content.companyName(), content.title(), content.roleName(),
                    content.industry(), content.companySize(), content.careerLevel(), content.description(),
                    content.responsibilities(), content.location(), content.deadline(), status,
                    content.requiredSkills(), content.preferredSkills(), hash);
        }
        jobs.saveAndFlush(job);
        if (changed) {
            jdbc.update("""
                    INSERT INTO job_source_revisions(job_id, observed_at, content_hash, snapshot_json,
                        terms_url, permission_evidence, policy_reviewed_on) VALUES (?,?,?,?,?,?,?)
                    """, job.getId(), Timestamp.from(request.observedAt()), hash, snapshot,
                    policy.termsUrl(), policy.permissionEvidence(), java.sql.Date.valueOf(policy.reviewedOn()));
        }
        return new Result(JobResponse.from(job), existing.isEmpty(), changed);
    }

    public record Revision(long id, java.time.Instant observedAt, String contentHash, String snapshotJson,
                           String termsUrl, String permissionEvidence, LocalDate policyReviewedOn) { }
    @Transactional(readOnly = true)
    public List<Revision> revisions(long jobId) {
        requireEnabled();
        if (!jobs.existsById(jobId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "공고를 찾을 수 없습니다.");
        return jdbc.query("SELECT * FROM job_source_revisions WHERE job_id=? ORDER BY id", (row, index) ->
                new Revision(row.getLong("id"), row.getTimestamp("observed_at").toInstant(),
                        row.getString("content_hash"), row.getString("snapshot_json"), row.getString("terms_url"),
                        row.getString("permission_evidence"), row.getDate("policy_reviewed_on").toLocalDate()), jobId);
    }
    private record Snapshot(String sourceUrl, JobImportRequest.Content content, JobPostingStatus effectiveStatus) { }
    private void requireEnabled() {
        if (!policies.isEnabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "공고 수집이 활성화되지 않았습니다.");
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
