package com.donggeon.jobrecommendation.jobanalysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.donggeon.jobrecommendation.domain.UserProfile;

/** Conservative, evidence-backed version of the six-part career rubric. */
final class RubricScorer {
    private RubricScorer() {}

    record Criterion(String requirement, String postingEvidence, int weight, Integer level, String status,
                     String profileEvidence, double evidenceQuality, String reason) {
        Criterion(String requirement, String postingEvidence, int weight, Integer level, String status,
                  String profileEvidence, double evidenceQuality) {
            this(requirement, postingEvidence, weight, level, status, profileEvidence, evidenceQuality, null);
        }
    }
    record Category(String key, String label, int weight, BigDecimal points, BigDecimal lowerPoints,
                    BigDecimal upperPoints, List<Criterion> criteria) {}
    record Result(List<Category> categories, BigDecimal total, int appliedWeight, BigDecimal evidenceCoverage,
                  String evidenceLevel, String eligibility, List<String> eligibilityNotes,
                  BigDecimal lowerTotal, BigDecimal upperTotal, boolean pending, String policyVersion,
                  List<Criterion> conditions) {
        Result {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }
        Result(List<Category> categories, BigDecimal total, int appliedWeight, BigDecimal evidenceCoverage,
               String evidenceLevel, String eligibility, List<String> eligibilityNotes,
               BigDecimal lowerTotal, BigDecimal upperTotal, boolean pending, String policyVersion) {
            this(categories, total, appliedWeight, evidenceCoverage, evidenceLevel, eligibility, eligibilityNotes,
                    lowerTotal, upperTotal, pending, policyVersion, List.of());
        }
    }

    static final String POLICY_VERSION = "career-evidence-score-v4";
    private static final BigDecimal[] SCORE_FACTORS = {
            new BigDecimal("0.00"), new BigDecimal("0.35"), new BigDecimal("0.65"),
            new BigDecimal("0.85"), new BigDecimal("1.00")};

    private static final Pattern ACTION = Pattern.compile("개발|구현|설계|구축|운영|개선|해결|검증|배포|유지보수|분석|자동화");
    private static final Pattern DOMAIN = Pattern.compile("금융|의료|헬스케어|커머스|전자상거래|게임|제조|물류|교육|보안|공공|광고|미디어|통신");
    private static final Set<String> STOP = Set.of("경험", "업무", "관련", "기반", "서비스", "시스템", "및", "또는", "사용", "수행", "개발", "운영", "설계", "구축", "프로젝트", "역량", "기술", "필수", "우대");

    static Result score(UserProfile profile, StructuredPosting posting,
                        List<JobMatchScorer.CertificateMatch> requiredCertificates,
                        List<JobMatchScorer.PreferenceMatch> preferredCriteria,
                        List<JobMatchScorer.CertificateMatch> preferredCertificates) {
        var snapshot = ProfileSnapshot.of(profile);
        var evidence = snapshot.evidenceItems();
        var categories = new ArrayList<Category>();
        categories.add(category("work", "핵심 업무 경험", 35, posting.responsibilities().stream()
                .map(item -> assess(item.value(), item.evidence(), important(item.evidence()), evidence, false)).toList()));

        var technology = new ArrayList<Criterion>();
        Set<String> seen = new LinkedHashSet<>();
        for (var item : posting.requiredSkills()) if (seen.add(key(item.value())))
            technology.add(assess(item.value(), item.evidence(), 2, evidence, true));
        for (var item : posting.dutySkills()) if (seen.add(key(item.value())))
            technology.add(assess(item.value(), item.evidence(), important(item.evidence()), evidence, true));
        categories.add(category("technology", "기술·지식 적합성", 25, technology));

        var problem = posting.responsibilities().stream().filter(item ->
                Pattern.compile("문제|개선|운영|유지보수|장애|검증|최적화|자동화").matcher(item.value()).find())
                .map(item -> assess(item.value(), item.evidence(), important(item.evidence()), evidence, false)).toList();
        categories.add(category("problem", "문제 해결·운영 역량", 15, problem));

        var domains = posting.responsibilities().stream().filter(item -> DOMAIN.matcher(item.value()).find())
                .map(item -> assess(item.value(), item.evidence(), important(item.evidence()), evidence, false)).toList();
        categories.add(category("domain", "산업·업무 도메인", 10, domains));

        var preferences = new ArrayList<Criterion>();
        if (!preferredCriteria.isEmpty()) {
            for (var item : preferredCriteria) preferences.add(new Criterion(item.condition(), item.evidence(), 1,
                    item.matched() ? 4 : null, item.matched() ? "확인됨" : "미확인",
                    String.join(", ", item.profileEvidence()), item.matched() ? 1 : 0));
        } else {
            for (var item : posting.preferredSkills()) preferences.add(assess(item.value(), item.evidence(), 1, evidence, true));
            for (var item : preferredCertificates) preferences.add(new Criterion(item.condition(), item.evidence(), 1,
                    item.matched() ? 4 : null, item.matched() ? "확인됨" : "미확인",
                    String.join(", ", item.matchedCertificates()), item.matched() ? 1 : 0));
        }
        categories.add(category("preferred", "우대조건", 10, preferences));
        var userPreferences = new ArrayList<Criterion>();
        String role = posting.roleName().value();
        if (role != null && !snapshot.targetRoles().isEmpty()) {
            boolean matches = snapshot.targetRoles().stream().anyMatch(value -> key(value).equals(key(role)));
            userPreferences.add(new Criterion("희망 직무", posting.roleName().evidence(), 1, matches ? 4 : 0,
                    matches ? "확인됨" : "확인된 불일치", String.join(", ", snapshot.targetRoles()), 1));
        }
        String location = posting.location().value();
        if (location != null && !snapshot.preferredLocations().isEmpty()) {
            boolean matches = snapshot.preferredLocations().stream().anyMatch(value -> key(location).contains(key(value)));
            userPreferences.add(new Criterion("희망 지역", posting.location().evidence(), 1, matches ? 4 : 0,
                    matches ? "확인됨" : "확인된 불일치", String.join(", ", snapshot.preferredLocations()), 1));
        }
        categories.add(category("userPreference", "사용자 선호", 5, userPreferences));

        var conditions = new ArrayList<Criterion>();
        Set<String> required = new LinkedHashSet<>();
        for (var skill : posting.requiredSkills()) required.add(key(skill.value()));
        for (var criterion : technology) if (required.contains(key(criterion.requirement()))) {
            boolean direct = criterion.level() != null && criterion.level() >= 3;
            conditions.add(new Criterion(criterion.requirement(), criterion.postingEvidence(), 1, direct ? 4 : null,
                    direct ? "확인됨" : "미확인", direct ? criterion.profileEvidence() : "", direct ? 1 : 0));
        }
        for (var certificate : requiredCertificates) {
            // The legacy matcher checks names, not language scores, grades or validity periods.
            boolean verified = certificate.matched() && !Pattern.compile("\\d|이상|이하|등급|점수|유효|기한")
                    .matcher(certificate.condition() + " " + certificate.evidence()).find();
            conditions.add(new Criterion(certificate.condition(), certificate.evidence(), 1, verified ? 4 : null,
                    verified ? "확인됨" : "미확인", String.join(", ", certificate.matchedCertificates()), verified ? 1 : 0));
        }
        for (var qualification : posting.qualifications()) if (!qualification.value().strip().startsWith("우대")) conditions.add(new Criterion(qualification.value(),
                qualification.evidence(), 1, null, "미확인", "", 0));
        return withConditions(categories, conditions);
    }

    /** Replays saved criterion levels under the current factor policy, including older v1 analyses. */
    static Result recalculate(Result saved) {
        var categories = saved.categories().stream().map(old -> category(old.key(), old.label(), old.weight(),
                old.criteria().stream().map(c -> new Criterion(c.requirement(), c.postingEvidence(), c.weight(),
                        "미확인".equals(c.status()) ? null : c.level(), c.status(), c.profileEvidence(),
                        c.evidenceQuality(), c.reason())).toList())).toList();
        return saved.conditions().isEmpty() ? result(categories, saved.eligibility(), saved.eligibilityNotes())
                : withConditions(categories, saved.conditions());
    }

    /** Accepts only a complete, grounded evaluation of the frozen posting criteria. */
    static Result evaluate(Result base, List<JobAiClient.EvaluationCriterion> criteria,
                           List<JobAiClient.Rating> ratings, List<JobAiClient.ProfileItem> profile) {
        if (base == null || criteria.size() != ratings.size()) throw new JobAiException("LLM_INVALID_OUTPUT");
        var expected = new java.util.HashMap<String, JobAiClient.EvaluationCriterion>();
        criteria.forEach(c -> expected.put(c.criterionId(), c));
        var byId = new java.util.HashMap<String, JobAiClient.Rating>();
        for (var rating : ratings) {
            var criterion = expected.get(rating.criterionId());
            Integer level = rating.level();
            if (criterion == null || byId.put(rating.criterionId(), rating) != null
                    || rating.status() == null || rating.reason() == null || rating.reason().isBlank()
                    || rating.profileEvidence() == null
                    || !Set.of("VERIFIED", "PARTIAL", "UNVERIFIED", "VERIFIED_MISMATCH").contains(rating.status())
                    || ("UNVERIFIED".equals(rating.status()) ? level != null : level == null)
                    || (level != null && (level < 0 || level > 4))
                    || (level != null && (level == 0) != "VERIFIED_MISMATCH".equals(rating.status()))
                    || ("BINARY".equals(criterion.evaluationType()) && level != null && level != 0 && level != 4)
                    || (level != null && rating.profileEvidence().isEmpty()))
                throw new JobAiException("LLM_INVALID_OUTPUT");
            for (var ref : rating.profileEvidence()) {
                if (ref.quote() == null || ref.quote().strip().length() < 2 || profile.stream().noneMatch(item ->
                        item.id().equals(ref.itemId()) && quoteKey(item.text()).contains(quoteKey(ref.quote()))))
                    throw new JobAiException("LLM_INVALID_OUTPUT");
            }
        }
        var categories = new ArrayList<Category>();
        for (var category : base.categories()) {
            if (category.key().equals("userPreference")) { categories.add(category); continue; }
            var updated = new ArrayList<Criterion>();
            for (int i = 0; i < category.criteria().size(); i++) {
                var old = category.criteria().get(i);
                var rating = byId.get(category.key() + ":" + i);
                if (rating == null) throw new JobAiException("LLM_INVALID_OUTPUT");
                String status = rating.level() == null ? "미확인" : rating.level() == 0 ? "확인된 불일치" : "확인됨";
                String quotes = rating.profileEvidence().stream().map(JobAiClient.ProfileRef::quote)
                        .collect(java.util.stream.Collectors.joining(" / "));
                updated.add(new Criterion(old.requirement(), old.postingEvidence(), old.weight(), rating.level(),
                        status, quotes, rating.level() == null ? 0 : "PARTIAL".equals(rating.status()) ? .5 : 1, rating.reason()));
            }
            categories.add(category(category.key(), category.label(), category.weight(), updated));
        }
        if (!base.conditions().isEmpty()) {
            var conditions = new ArrayList<Criterion>();
            for (int i = 0; i < base.conditions().size(); i++) {
                var old = base.conditions().get(i);
                var rating = byId.get("eligibility:" + i);
                if (rating == null) throw new JobAiException("LLM_INVALID_OUTPUT");
                // An explicit, grounded rule match (e.g. a registered certificate) survives an AI omission.
                boolean knownCertificate = Integer.valueOf(4).equals(old.level()) && !old.profileEvidence().isBlank()
                        && profile.stream().anyMatch(item -> item.kind().equals("certificate")
                        && quoteKey(item.text()).contains(quoteKey(old.profileEvidence())));
                if (rating.level() == null && knownCertificate) { conditions.add(old); continue; }
                String quotes = rating.profileEvidence().stream().map(JobAiClient.ProfileRef::quote)
                        .collect(java.util.stream.Collectors.joining(" / "));
                conditions.add(new Criterion(old.requirement(), old.postingEvidence(), 1, rating.level(),
                        rating.level() == null ? "미확인" : rating.level() == 0 ? "확인된 불일치" : "확인됨",
                        quotes, rating.level() == null ? 0 : 1, rating.reason()));
            }
            return withConditions(categories, conditions);
        }
        var notes = new ArrayList<>(base.eligibilityNotes());
        for (var category : categories) if (category.key().equals("technology"))
            for (var criterion : category.criteria()) if (Integer.valueOf(4).equals(criterion.level()))
                notes.remove("필수 기술 사용 경험 확인 필요: " + criterion.requirement());
        return result(categories, "미충족".equals(base.eligibility()) ? "미충족" : notes.isEmpty() ? "충족" : "확인 필요", notes);
    }

    private static String quoteKey(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", " ").strip();
    }

    private static Result withConditions(List<Category> categories, List<Criterion> conditions) {
        boolean unmet = conditions.stream().anyMatch(c -> Integer.valueOf(0).equals(c.level()));
        boolean unknown = conditions.stream().anyMatch(c -> c.level() == null);
        var notes = conditions.stream().filter(c -> !Integer.valueOf(4).equals(c.level()))
                .map(c -> (c.level() == null ? "지원조건 확인 필요: " : "지원조건 미충족: ") + c.requirement()).toList();
        var result = result(categories, unmet ? "미충족" : unknown ? "확인 필요" : "충족", notes);
        return new Result(result.categories(), result.total(), result.appliedWeight(), result.evidenceCoverage(),
                result.evidenceLevel(), result.eligibility(), result.eligibilityNotes(), result.lowerTotal(),
                result.upperTotal(), result.pending(), result.policyVersion(), conditions);
    }

    private static Result result(List<Category> categories, String eligibility, List<String> eligibilityNotes) {
        int applied = categories.stream().filter(c -> !c.criteria().isEmpty()).mapToInt(Category::weight).sum();
        boolean pending = categories.stream().flatMap(c -> c.criteria().stream()).anyMatch(c -> c.level() == null);
        boolean hasJobCriteria = categories.stream().anyMatch(c -> !c.key().equals("userPreference") && !c.criteria().isEmpty());
        BigDecimal lower = !hasJobCriteria ? null : normalizedTotal(categories, applied, false);
        BigDecimal upper = !hasJobCriteria ? null : normalizedTotal(categories, applied, true);
        // Submitted-evidence score: unsupported criteria receive no earned points, but remain null
        // (unverified) in the evidence record, distinct from an explicit mismatch.
        BigDecimal total = lower;
        double coverageNumerator = 0;
        double coverageDenominator = 0;
        for (var category : categories) {
            if (category.criteria().isEmpty()) continue;
            int criterionWeight = category.criteria().stream().mapToInt(Criterion::weight).sum();
            for (var criterion : category.criteria()) {
                double share = (double) category.weight() * criterion.weight() / criterionWeight;
                coverageNumerator += share * criterion.evidenceQuality();
                coverageDenominator += share;
            }
        }
        BigDecimal coverage = coverageDenominator == 0 ? null : BigDecimal.valueOf(coverageNumerator * 100 / coverageDenominator)
                .setScale(1, RoundingMode.HALF_UP);
        String coverageLevel = coverage == null ? "평가 불가" : coverage.compareTo(BigDecimal.valueOf(80)) >= 0 ? "높음"
                : coverage.compareTo(BigDecimal.valueOf(60)) >= 0 ? "보통" : "낮음";
        return new Result(categories, total, applied, coverage, coverageLevel, eligibility,
                List.copyOf(eligibilityNotes), lower, upper, pending, POLICY_VERSION);
    }

    private static Category category(String key, String label, int weight, List<Criterion> criteria) {
        if (criteria.isEmpty()) return new Category(key, label, weight, null, null, null, List.of());
        var category = new Category(key, label, weight, null, null, null, List.copyOf(criteria));
        var lower = rawPoints(category, false).setScale(1, RoundingMode.HALF_UP);
        var upper = rawPoints(category, true).setScale(1, RoundingMode.HALF_UP);
        return new Category(key, label, weight, lower, lower, upper, category.criteria());
    }

    private static BigDecimal normalizedTotal(List<Category> categories, int applied, boolean upper) {
        var points = categories.stream().filter(c -> !c.criteria().isEmpty())
                .map(c -> rawPoints(c, upper)).reduce(BigDecimal.ZERO, BigDecimal::add);
        return points.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(applied), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal rawPoints(Category category, boolean upper) {
        int sum = category.criteria().stream().mapToInt(Criterion::weight).sum();
        var factors = category.criteria().stream().map(item ->
                (item.level() == null ? upper ? BigDecimal.ONE : BigDecimal.ZERO : SCORE_FACTORS[item.level()])
                        .multiply(BigDecimal.valueOf(item.weight()))).reduce(BigDecimal.ZERO, BigDecimal::add);
        return BigDecimal.valueOf(category.weight()).multiply(factors)
                .divide(BigDecimal.valueOf(sum), 12, RoundingMode.HALF_UP);
    }

    private static Criterion assess(String requirement, String quote, int weight,
                                    List<JobAiClient.ProfileItem> evidence, boolean technology) {
        String normalized = key(requirement);
        String[] terms = terms(requirement);
        int bestLevel = 0;
        double bestQuality = 0;
        String bestEvidence = "";
        for (var item : evidence) {
            String text = item.text();
            String candidate = key(text);
            boolean exact = !normalized.isEmpty() && candidate.contains(normalized);
            int shared = 0;
            for (String term : terms) if (candidate.contains(term)) shared++;
            String narrative = text.split("\\(기술:", 2)[0];
            // A stack list proves mention, while a project description must link the specific
            // technology to implementation before it can prove direct use.
            boolean detailed = (item.kind().equals("project") || item.kind().equals("experience"))
                    && ACTION.matcher(narrative).find() && (!technology || key(narrative).contains(normalized));
            int level = exact ? detailed ? 4 : technology ? 1 : 3 : shared >= 2 ? 2 : 0;
            double quality = level == 0 ? 0 : level == 2 ? .5 : detailed ? 1 : .5;
            if (level > bestLevel || level == bestLevel && quality > bestQuality) {
                bestLevel = level;
                bestQuality = quality;
                bestEvidence = text;
            }
        }
        return new Criterion(requirement, quote, weight, bestLevel == 0 ? null : bestLevel,
                bestLevel == 0 ? "미확인" : "확인됨",
                bestEvidence, bestQuality);
    }

    private static int important(String evidence) {
        return evidence != null && Pattern.compile("핵심|필수|반드시").matcher(evidence).find() ? 2 : 1;
    }

    private static String key(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String[] terms(String text) {
        return java.util.Arrays.stream(Normalizer.normalize(text, Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(term -> term.length() >= 2 && !STOP.contains(term)).distinct().toArray(String[]::new);
    }
}
