package com.donggeon.jobrecommendation.jobanalysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.donggeon.jobrecommendation.domain.JobAnalysis;
import com.donggeon.jobrecommendation.domain.JobAnalysisDraft;
import com.donggeon.jobrecommendation.domain.User;
import com.donggeon.jobrecommendation.domain.UserProfile;
import com.donggeon.jobrecommendation.extraction.JobTextExtractionService;
import com.donggeon.jobrecommendation.profile.CurrentUser;
import com.donggeon.jobrecommendation.profile.UserProfileRepository;
import com.donggeon.jobrecommendation.profile.UserRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Fixed pipeline: confirmed text, AI structuring, deterministic score, AI role analysis.
 * No transaction stays open while the AI service works, and the role analysis can fail or be
 * retried without changing the stored score.
 */
@Service
public class JobAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(JobAnalysisService.class);
    public static final int MIN_TEXT = 20;
    public static final int MAX_TEXT = JobTextExtractionService.MAX_TEXT;
    static final Duration DRAFT_TTL = Duration.ofHours(24);

    private final CurrentUser currentUser;
    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final JobAnalysisRepository analyses;
    private final JobAnalysisDraftRepository drafts;
    private final JobTextExtractionService extraction;
    private final JobAiClient ai;
    private final JobMatchScorer scorer;
    private final JobAnalysisRetention retention;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnly;

    public JobAnalysisService(CurrentUser currentUser, UserRepository users, UserProfileRepository profiles,
                              JobAnalysisRepository analyses, JobAnalysisDraftRepository drafts,
                              JobTextExtractionService extraction, JobAiClient ai, JobMatchScorer scorer,
                              JobAnalysisRetention retention, JsonMapper json, PlatformTransactionManager transactions) {
        this.currentUser = currentUser;
        this.users = users;
        this.profiles = profiles;
        this.analyses = analyses;
        this.drafts = drafts;
        this.extraction = extraction;
        this.ai = ai;
        this.scorer = scorer;
        this.retention = retention;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /** Extracts text for review only. Nothing is scored until the user confirms the text. */
    public JobAnalysisResponse.Draft createDraft(MultipartFile file) {
        var result = extraction.extract(file);
        String identity = currentUser.identity();
        JobAnalysisDraft draft = tx.execute(status -> {
            User user = users.findByEmail(identity).orElseGet(() -> users.save(new User(identity, "OnFit User")));
            return drafts.save(new JobAnalysisDraft(user, result.text(), result.method(), result.pages(), result.truncated()));
        });
        return new JobAnalysisResponse.Draft(draft.getId(), result.text(), result.method(), result.pages(), result.truncated());
    }

    public JobAnalysisResponse analyze(JobAnalysisRequest request) {
        long started = System.nanoTime();
        String text = normalize(request.text());
        if (text.length() < MIN_TEXT) throw bad("공고 텍스트를 20자 이상 입력해 주세요.");
        String sourceHash = sha256(text);
        String identity = currentUser.identity();
        // Asked before the read transaction, so no transaction stays open during a call to the AI service.
        JobAiClient.PromptVersions versions = ai.promptVersions();

        Prepared prepared = readOnly.execute(status -> {
            UserProfile profile = profiles.findByUserEmail(identity).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "비교할 프로필을 먼저 저장해 주세요."));
            Long draftId = request.inputType() == JobAnalysis.InputType.FILE ? checkDraft(request, identity, sourceHash) : null;
            ProfileSnapshot snapshot = ProfileSnapshot.of(profile); // also loads every collection the scorer reads
            String snapshotJson = json.writeValueAsString(snapshot);
            String profileHash = sha256(snapshotJson);
            Long existing = analyses.findFirstByUserIdAndSourceHashAndProfileHashAndCalculatorVersionAndCreatedAtGreaterThanEqualOrderByIdDesc(
                    profile.getUser().getId(), sourceHash, profileHash, JobMatchScorer.CALCULATOR_VERSION, retention.cutoff())
                    .filter(found -> fromCurrentPrompts(found, versions))
                    .map(JobAnalysis::getId).orElse(null);
            return new Prepared(profile, snapshot, snapshotJson, profileHash, draftId, existing);
        });
        if (prepared.existingId() != null) {
            log.info("job_analysis_reused analysis_id={}", prepared.existingId());
            return get(prepared.existingId());
        }

        JobAiClient.Extraction extracted;
        try {
            extracted = ai.extract(text);
        } catch (JobAiException ex) {
            log.warn("job_analysis_failed stage=extract code={}", ex.getCode());
            throw toHttp(ex);
        }
        JobAnalysis.Scoring scoring = scorer.score(prepared.profile(), extracted.posting());
        JobAiClient.RoleResult role = null;
        String roleError = null;
        try {
            role = ai.analyzeRole(text, extracted.posting(), prepared.snapshot().evidenceItems(), facts(scoring));
        } catch (JobAiException ex) {
            // The score stands on its own; the explanation can be retried later.
            roleError = ex.getCode();
            log.warn("job_analysis_partial stage=analyze_role code={}", roleError);
        }

        final JobAiClient.RoleResult roleResult = role;
        final String roleFailure = roleError;
        Long id = tx.execute(status -> {
            User user = users.findByEmail(identity).orElseThrow();
            JobAnalysisDraft draft = prepared.draftId() == null ? null : drafts.getReferenceById(prepared.draftId());
            var analysis = new JobAnalysis(user, request.inputType(), draft, text, sourceHash, prepared.snapshotJson(),
                    prepared.profileHash(), json.writeValueAsString(extracted.posting()), extracted.model(),
                    extracted.promptVersion(), scoring);
            if (roleResult != null) {
                analysis.completeRoleAnalysis(json.writeValueAsString(roleResult.analysis()), roleResult.model(),
                        roleResult.promptVersion());
            } else {
                analysis.failRoleAnalysis(roleFailure);
            }
            return analyses.save(analysis).getId();
        });
        log.info("job_analysis_created analysis_id={} input_type={} semantic_status={} role_status={} duration_ms={}",
                id, request.inputType(), scoring.semanticStatus(), roleResult != null ? "COMPLETED" : "FAILED",
                (System.nanoTime() - started) / 1_000_000);
        return get(id);
    }

    public JobAnalysisResponse get(Long id) {
        String identity = currentUser.identity();
        return readOnly.execute(status -> toResponse(findOwned(id, identity), identity));
    }

    /** Re-runs only the role analysis, from the stored text, structure, profile snapshot and score. */
    public JobAnalysisResponse retryRoleAnalysis(Long id) {
        String identity = currentUser.identity();
        record Input(String text, StructuredPosting posting, ProfileSnapshot snapshot, JobAiClient.ScoreFacts facts) {}
        Input input = readOnly.execute(status -> {
            JobAnalysis analysis = findOwned(id, identity);
            if (analysis.getRoleStatus() == JobAnalysis.RoleStatus.COMPLETED) return null;
            var detail = json.readValue(analysis.getScoreDetail(), JobMatchScorer.Detail.class);
            return new Input(analysis.getSourceText(),
                    json.readValue(analysis.getStructuredPosting(), StructuredPosting.class),
                    json.readValue(analysis.getProfileSnapshot(), ProfileSnapshot.class),
                    new JobAiClient.ScoreFacts(detail.matchedRequired(), detail.matchedPreferred(), detail.missing()));
        });
        if (input == null) return get(id);
        try {
            var role = ai.analyzeRole(input.text(), input.posting(), input.snapshot().evidenceItems(), input.facts());
            tx.executeWithoutResult(status -> findOwned(id, identity).completeRoleAnalysis(
                    json.writeValueAsString(role.analysis()), role.model(), role.promptVersion()));
            log.info("job_analysis_role_retried analysis_id={} role_status=COMPLETED", id);
        } catch (JobAiException ex) {
            tx.executeWithoutResult(status -> findOwned(id, identity).failRoleAnalysis(ex.getCode()));
            log.warn("job_analysis_role_retried analysis_id={} role_status=FAILED code={}", id, ex.getCode());
            throw toHttp(ex);
        }
        return get(id);
    }

    private Long checkDraft(JobAnalysisRequest request, String identity, String sourceHash) {
        if (request.draftId() == null || request.confirmedTextHash() == null) {
            throw bad("파일에서 추출한 텍스트를 확인한 뒤 분석해 주세요.");
        }
        JobAnalysisDraft draft = drafts.findByIdAndUserEmail(request.draftId(), identity)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "추출한 파일 텍스트를 찾을 수 없습니다."));
        if (draft.getCreatedAt().isBefore(Instant.now().minus(DRAFT_TTL))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "추출한 지 오래된 파일입니다. 파일을 다시 올려 주세요.");
        }
        if (!request.confirmedTextHash().equals(sourceHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "확인한 뒤 텍스트가 바뀌었습니다. 수정한 텍스트를 다시 확인해 주세요.");
        }
        return draft.getId();
    }

    private JobAnalysis findOwned(Long id, String identity) {
        // Another user's analysis answers exactly like a missing one.
        return analyses.findByIdAndUserEmailAndCreatedAtGreaterThanEqual(id, identity, retention.cutoff())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "분석 결과를 찾을 수 없습니다."));
    }

    private JobAnalysisResponse toResponse(JobAnalysis analysis, String identity) {
        String currentHash = profiles.findByUserEmail(identity)
                .map(profile -> sha256(json.writeValueAsString(ProfileSnapshot.of(profile)))).orElse(null);
        var detail = json.readValue(analysis.getScoreDetail(), JobMatchScorer.Detail.class);
        var snapshot = json.readValue(analysis.getProfileSnapshot(), ProfileSnapshot.class);
        var score = new JobAnalysisResponse.Score(analysis.getTotalScore(), JobMatchScorer.maxTotal(analysis.getSemanticStatus()),
                analysis.getRequiredScore(), analysis.getPreferredScore(), analysis.getSemanticScore(),
                analysis.getExperienceScore(), analysis.getPreferenceScore(), analysis.getSemanticStatus(),
                detail.noRequirements(), detail.matchedRequired(), detail.matchedPreferred(), detail.missing(),
                detail.evidence());
        var versions = new JobAnalysisResponse.Versions(analysis.getExtractionModel(), analysis.getExtractionPromptVersion(),
                analysis.getCalculatorVersion(), analysis.getSemanticModelVersion(), analysis.getRoleModel(),
                analysis.getRolePromptVersion());
        return new JobAnalysisResponse(analysis.getId(), analysis.getInputType(), analysis.getCreatedAt(),
                !analysis.getProfileHash().equals(currentHash), json.readTree(analysis.getStructuredPosting()), score,
                analysis.getRoleStatus(), analysis.getRoleErrorCode() == null ? null : message(analysis.getRoleErrorCode()),
                analysis.getRoleAnalysis() == null ? null : json.readTree(analysis.getRoleAnalysis()),
                snapshot.labels(), versions);
    }

    /**
     * A stored analysis is reused only if the AI service still runs the prompts that made it. A failed role
     * analysis has no prompt of its own; retrying it uses the current one. Unknown versions keep the stored result.
     */
    static boolean fromCurrentPrompts(JobAnalysis analysis, JobAiClient.PromptVersions versions) {
        if (versions == null) return true;
        return versions.extract().equals(analysis.getExtractionPromptVersion())
                && (analysis.getRoleStatus() != JobAnalysis.RoleStatus.COMPLETED
                    || versions.role().equals(analysis.getRolePromptVersion()));
    }

    private JobAiClient.ScoreFacts facts(JobAnalysis.Scoring scoring) {
        var detail = json.readValue(scoring.detailJson(), JobMatchScorer.Detail.class);
        return new JobAiClient.ScoreFacts(detail.matchedRequired(), detail.matchedPreferred(), detail.missing());
    }

    /** Same rule as the frontend: CRLF/CR become LF and control characters and spaces at both ends go. */
    static String normalize(String text) {
        return text.replaceAll("\r\n?", "\n").trim();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static ResponseStatusException toHttp(JobAiException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "LLM_DISABLED", "LLM_RATE_LIMITED", "LLM_UNAVAILABLE", "LLM_AUTH" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "LLM_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            case "LLM_REFUSAL" -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return new ResponseStatusException(status, message(ex.getCode()));
    }

    static String message(String code) {
        return switch (code) {
            case "LLM_DISABLED" -> "AI 공고 분석이 아직 설정되지 않았습니다. 관리자에게 문의해 주세요.";
            case "LLM_TIMEOUT" -> "AI 분석 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.";
            case "LLM_RATE_LIMITED" -> "AI 분석 요청이 많습니다. 잠시 후 다시 시도해 주세요.";
            case "LLM_REFUSAL" -> "AI가 이 텍스트를 분석하지 못했습니다. 채용공고 내용인지 확인해 주세요.";
            default -> "AI 분석에 실패했습니다. 잠시 후 다시 시도해 주세요.";
        };
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Prepared(UserProfile profile, ProfileSnapshot snapshot, String snapshotJson, String profileHash,
                            Long draftId, Long existingId) {}
}
