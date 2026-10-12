package com.donggeon.jobrecommendation.alerts;

import com.donggeon.jobrecommendation.domain.*;
import com.donggeon.jobrecommendation.profile.*;
import com.donggeon.jobrecommendation.recommendation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertService {
    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private final JdbcTemplate db;
    private final CurrentUser current;
    private final UserProfileRepository profiles;
    private final JobService jobs;
    private final RecommendationCalculator calculator;
    private final SemanticScoreProvider semantic;
    private final AlertMail mail;
    private final VerificationMailQuota verificationQuota;
    private final CompanyGroups companyGroups;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String appUrl;
    private final SecureRandom random = new SecureRandom();
    public AlertService(JdbcTemplate db, CurrentUser current, UserProfileRepository profiles, JobService jobs,
            RecommendationCalculator calculator, SemanticScoreProvider semantic, AlertMail mail,
            VerificationMailQuota verificationQuota, CompanyGroups companyGroups, Clock clock,
            PlatformTransactionManager manager, @Value("${onfit.alerts.app-url:http://localhost:5173}") String appUrl) {
        this.db=db; this.current=current; this.profiles=profiles; this.jobs=jobs; this.calculator=calculator;
        this.semantic=semantic; this.mail=mail; this.clock=clock; this.tx=new TransactionTemplate(manager);
        this.verificationQuota=verificationQuota;
        this.companyGroups=companyGroups;
        this.appUrl=appUrl;
    }
    private long owner(boolean lock) {
        List<Long> ids = db.query("SELECT id FROM app_users WHERE email=?" + (lock ? " FOR UPDATE" : ""),
                (r,i)->r.getLong(1), current.identity());
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "먼저 프로필을 저장해 주세요.");
        return ids.getFirst();
    }
    private void ensure(long id) {
        if (db.queryForObject("SELECT COUNT(*) FROM job_alert_subscriptions WHERE user_id=?", Integer.class, id)==0)
            db.update("INSERT INTO job_alert_subscriptions(user_id,subscribed_at,evaluated_at) VALUES(?,?,?)",
                    id, now(), now());
    }
    private Timestamp now() { return Timestamp.from(clock.instant()); }
    private static Instant instant(Object value) {
        if(value instanceof Timestamp timestamp) return timestamp.toInstant();
        if(value instanceof OffsetDateTime timestamp) return timestamp.toInstant();
        throw new IllegalStateException("Unexpected timestamp representation");
    }
    private Map<String,Object> subscription(long id) {
        return db.queryForMap("SELECT * FROM job_alert_subscriptions WHERE user_id=?", id);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> mine() {
        long id=owner(false);
        if (db.queryForObject("SELECT COUNT(*) FROM job_alert_subscriptions WHERE user_id=?", Integer.class, id)==0)
            return Map.of("email","", "verified",false,"enabled",false,"companies",List.of(),
                    "minScore",60,"mailAvailable",mail.available(),"expandedCompanies",List.of());
        var s=subscription(id);
        return Map.of("email", Objects.toString(s.get("email"),""), "verified",s.get("verified"),
                "enabled",s.get("enabled"),"companies",companies((String)s.get("companies")),
                "minScore",s.get("min_score"),"mailAvailable",mail.available(),
                "expandedCompanies",companyGroups.expand(companies((String)s.get("companies"))));
    }
    @Transactional
    public void save(AlertController.Settings settings) {
        long id=owner(true); ensure(id);
        if (settings.enabled() && !Boolean.TRUE.equals(subscription(id).get("verified")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"알림 이메일을 먼저 인증해 주세요.");
        if (settings.companies().stream().anyMatch(n->n.contains("\n") || n.contains("\r")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"기업명에 줄바꿈을 넣을 수 없습니다.");
        List<String> names=settings.companies().stream().map(AlertService::companyName).filter(n->!n.isEmpty()).distinct().toList();
        if (settings.enabled() && names.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"관심 기업을 한 곳 이상 입력해 주세요.");
        // Re-enabling starts a new baseline; previously sent deliveries remain deduplicated.
        boolean starting=settings.enabled() && !Boolean.TRUE.equals(subscription(id).get("enabled"));
        db.update("UPDATE job_alert_subscriptions SET enabled=?, companies=?, min_score=? WHERE user_id=?",
                settings.enabled(),String.join("\n",names),settings.minScore(),id);
        if (starting) db.update("UPDATE job_alert_subscriptions SET subscribed_at=? WHERE user_id=?",now(),id);
        if (!settings.enabled()) db.update("UPDATE job_alert_deliveries SET state='CANCELLED' WHERE user_id=? AND state='PENDING'",id);
    }
    @Transactional
    public void requestVerification(String email) {
        if (!mail.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"이메일 발송 설정이 필요합니다.");
        long id=owner(true); ensure(id);
        Object sent=subscription(id).get("verification_sent_at");
        if (sent!=null && instant(sent).plusSeconds(60).isAfter(clock.instant()))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"인증번호는 1분 후 다시 요청할 수 있습니다.");
        if (!verificationQuota.reserve(email))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "이 이메일 주소의 하루 인증 메일 한도(5회)를 초과했습니다. 한국 시간 자정 이후 다시 요청해 주세요.");
        String code="%06d".formatted(random.nextInt(1_000_000));
        db.update("""
            UPDATE job_alert_subscriptions SET email=?,verified=FALSE,enabled=FALSE,
            verification_hash=?,verification_expires_at=?,verification_sent_at=?,verification_attempts=0 WHERE user_id=?
            """,email.strip(),hash(id+":"+code),Timestamp.from(clock.instant().plusSeconds(900)),now(),id);
        db.update("UPDATE job_alert_deliveries SET state='CANCELLED' WHERE user_id=? AND state='PENDING'",id);
        try { mail.send(email.strip(),"[온핏] 알림 이메일 인증번호", "인증번호: "+code+"\n15분 안에 온핏 알림 설정에 입력해 주세요."); }
        catch (Exception failure) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요."); }
    }
    @Transactional
    public boolean verify(String code) {
        long id=owner(true); ensure(id); var s=subscription(id);
        Object expires=s.get("verification_expires_at");
        if (expires==null || !instant(expires).isAfter(clock.instant()) || ((Number)s.get("verification_attempts")).intValue()>=5)
            return false;
        db.update("UPDATE job_alert_subscriptions SET verification_attempts=verification_attempts+1 WHERE user_id=?",id);
        boolean valid=MessageDigest.isEqual(hash(id+":"+code).getBytes(StandardCharsets.UTF_8),
                Objects.toString(s.get("verification_hash"),"").getBytes(StandardCharsets.UTF_8));
        if (valid) db.update("UPDATE job_alert_subscriptions SET verified=TRUE,verification_hash=NULL,verification_expires_at=NULL WHERE user_id=?",id);
        return valid;
    }
    @Transactional(readOnly=true)
    public List<String> targets() {
        return db.query("SELECT companies FROM job_alert_subscriptions WHERE enabled=TRUE AND verified=TRUE",
                (r,i)->companyGroups.expand(companies(r.getString(1)))).stream().flatMap(List::stream).distinct().sorted().toList();
    }
    // The collector strips the same legal-form markers from source names ("(주)카카오" is stored as "카카오").
    static String companyName(String value) { return CompanyGroups.name(value); }
    private static List<String> companies(String names) { return names.isBlank()?List.of():List.of(names.split("\n")); }
    private boolean companyMatches(String actual,List<String> expected) {
        return companyGroups.matches(actual,expected);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private record Digest(long userId,String email,List<Long> ids,String body) { }
    public Map<String,Object> run() { return run(null); }
    public Map<String,Object> run(Instant before) {
        if (!mail.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"이메일 발송 설정이 필요합니다.");
        Instant cutoff=before==null?clock.instant():before;
        if(cutoff.isAfter(clock.instant().plusSeconds(300)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"알림 평가 기준 시각이 잘못되었습니다.");
        Timestamp lease=Timestamp.from(clock.instant().plusSeconds(1800));
        if (db.update("UPDATE job_alert_batch_lock SET locked_until=? WHERE id=1 AND locked_until<=?",lease,now())==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"알림 작업이 이미 실행 중입니다.");
        int sent=0,ambiguous=0,evaluated=0;
        long deadlineNanos=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
        try {
            List<Long> ids=db.query("SELECT user_id FROM job_alert_subscriptions WHERE enabled=TRUE AND verified=TRUE AND evaluated_at<? ORDER BY evaluated_at,user_id LIMIT 100",
                    (r,i)->r.getLong(1),Timestamp.from(cutoff));
            for (long id:ids) {
                if(System.nanoTime()>=deadlineNanos) break;
                evaluated++;
                Digest digest;
                try { digest=tx.execute(status->prepare(id,cutoff)); }
                catch (Exception failure) { log.error("job_alert_prepare_failure type={}",failure.getClass().getSimpleName()); continue; }
                if (digest==null) continue;
                // SENDING is committed before SMTP: a retry never blindly sends an uncertain previous delivery.
                try {
                    mail.send(digest.email(),"[온핏] 새로운 맞춤 채용 공고",digest.body());
                } catch (Exception failure) {
                    for (long delivery:digest.ids()) db.update("UPDATE job_alert_deliveries SET state='UNKNOWN' WHERE id=? AND state='SENDING'",delivery);
                    ambiguous++; log.error("job_alert_send_unknown type={}",failure.getClass().getSimpleName()); continue;
                }
                for (long delivery:digest.ids()) db.update("UPDATE job_alert_deliveries SET state='SENT',sent_at=? WHERE id=? AND state='SENDING'",now(),delivery);
                sent++;
            }
            return Map.of("evaluated",evaluated,"sentEmails",sent,"unknownEmails",ambiguous,
                    "remainingSubscriptions",db.queryForObject("SELECT COUNT(*) FROM job_alert_subscriptions WHERE enabled=TRUE AND verified=TRUE AND evaluated_at<?",Integer.class,Timestamp.from(cutoff)));
        } finally { db.update("UPDATE job_alert_batch_lock SET locked_until=? WHERE id=1 AND locked_until=?",now(),lease); }
    }
    private Digest prepare(long id,Instant cutoff) {
        db.queryForObject("SELECT user_id FROM job_alert_subscriptions WHERE user_id=? FOR UPDATE",Long.class,id);
        var s=subscription(id);
        if (!Boolean.TRUE.equals(s.get("enabled")) || !Boolean.TRUE.equals(s.get("verified"))) return null;
        Instant evaluated=clock.instant().isBefore(cutoff)?cutoff:clock.instant();
        db.update("UPDATE job_alert_subscriptions SET evaluated_at=? WHERE user_id=?",Timestamp.from(evaluated),id);
        UserProfile profile=profiles.findByUserId(id).orElse(null);
        if(profile==null) return null;
        var open=jobs.alertCandidates(companyGroups.expand(companies((String)s.get("companies")))).stream().filter(j->j.getOrigin()==JobOrigin.REAL
                && companyMatches(j.getCompanyName(),companies((String)s.get("companies")))).toList();
        var semantics=semantic.score(profile,open);
        Map<Long,JobPosting> eligible=new HashMap<>();
        for(JobPosting job:open) {
            BigDecimal score=calculator.calculate(profile,job,semantics.score(job.getId())).totalScore();
            if(score.compareTo((BigDecimal)s.get("min_score"))<0) continue;
            eligible.put(job.getId(),job);
            if(job.getCollectedAt()==null || !job.getCollectedAt().isAfter(instant(s.get("subscribed_at")))) continue;
            if(db.queryForObject("SELECT COUNT(*) FROM job_alert_deliveries WHERE user_id=? AND job_id=?",Integer.class,id,job.getId())==0)
                db.update("INSERT INTO job_alert_deliveries(user_id,job_id,state,score,created_at) VALUES(?,?,'PENDING',?,?)",id,job.getId(),score,now());
        }
        var pending=db.queryForList("SELECT id,job_id,score FROM job_alert_deliveries WHERE user_id=? AND state='PENDING' ORDER BY score DESC,job_id",id);
        List<Long> deliveries=new ArrayList<>();
        StringBuilder body=new StringBuilder("관심 기업에서 새로 확인한 맞춤 공고입니다.\n\n");
        for(var row:pending) {
            long delivery=((Number)row.get("id")).longValue();
            JobPosting job=eligible.get(((Number)row.get("job_id")).longValue());
            if(job==null) { db.update("UPDATE job_alert_deliveries SET state='CANCELLED' WHERE id=?",delivery); continue; }
            if(deliveries.size()>=10) continue;
            var score=calculator.calculate(profile,job,semantics.score(job.getId()));
            body.append(job.getCompanyName()).append(" · ").append(job.getTitle()).append("\n추천 점수: ")
                    .append(score.totalScore()).append("\n마감: ").append(job.getDeadline()==null?"원문 확인":job.getDeadline())
                    .append("\n일치 근거: ").append(String.join(", ",score.matchedEvidence()))
                    .append("\n누락 기술: ").append(String.join(", ",score.missingSkills()))
                    .append("\n").append(job.getSourceUrl()).append("\n\n");
            db.update("UPDATE job_alert_deliveries SET state='SENDING' WHERE id=? AND state='PENDING'",delivery);
            deliveries.add(delivery);
        }
        if(deliveries.isEmpty()) return null;
        body.append("추천 근거 확인 및 알림 해제: ").append(appUrl).append("/#/alerts\n");
        return new Digest(id,(String)s.get("email"),deliveries,body.toString());
    }
}
