package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.donggeon.jobrecommendation.alerts.*;
import com.donggeon.jobrecommendation.domain.*;
import com.donggeon.jobrecommendation.profile.CurrentUser;
import com.donggeon.jobrecommendation.seed.JobPostingRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:alerts_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "onfit.seed.enabled=false"})
@ActiveProfiles("test")
class AlertTests {
    @Autowired AlertService alerts;
    @Autowired JdbcTemplate db;
    @Autowired Clock clock;
    @Autowired JobPostingRepository jobs;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean AlertMail mail;
    @MockitoBean CurrentUser current;
    long user;
    @BeforeEach void setup() {
        when(current.identity()).thenReturn("alerts.owner@onfit.invalid");
        when(mail.available()).thenReturn(true);
        db.update("INSERT INTO app_users(email,display_name) VALUES('alerts.owner@onfit.invalid','Fixture')");
        user=db.queryForObject("SELECT id FROM app_users WHERE email='alerts.owner@onfit.invalid'",Long.class);
        db.update("INSERT INTO user_profiles(user_id) VALUES(?)",user);
        db.update("INSERT INTO job_alert_subscriptions(user_id,email,verified,enabled,companies,min_score,subscribed_at,evaluated_at) VALUES(?, ?,TRUE,TRUE,?,0,?,?)",
                user,"owner@example.test","Example",Timestamp.from(clock.instant().minusSeconds(60)),Timestamp.from(clock.instant().minusSeconds(60)));
    }
    @AfterEach void cleanup() { db.update("DELETE FROM app_users"); jobs.deleteAll(); db.update("DELETE FROM verification_mail_limits"); }
    JobPosting posting(String company,JobPostingStatus state,Instant collected) {
        JobPosting job=JobPosting.imported("wanted",UUID.randomUUID().toString(),collected);
        job.markSeen(collected);
        job.updateImported("https://www.wanted.co.kr/wd/1",company,"Backend","Backend",null,null,null,
                "Build APIs","Build APIs","Seoul",LocalDate.now(clock).plusDays(1),state,List.of(),List.of(),"fixture");
        return jobs.saveAndFlush(job);
    }
    void makeDue() { db.update("UPDATE job_alert_subscriptions SET evaluated_at=?",Timestamp.from(clock.instant().minusSeconds(1))); }
    @Test void newMatchingRealJobSentOnceWhileOldClosedAndOtherCompaniesExcluded() {
        posting("Example",JobPostingStatus.OPEN,clock.instant().minusSeconds(30));
        posting("Example",JobPostingStatus.OPEN,clock.instant().minusSeconds(120));
        posting("Example",JobPostingStatus.CLOSED,clock.instant().minusSeconds(30));
        posting("Example Subsidiary",JobPostingStatus.OPEN,clock.instant().minusSeconds(30));
        assertThat(alerts.run().get("sentEmails")).isEqualTo(1);
        makeDue(); assertThat(alerts.run().get("sentEmails")).isEqualTo(0);
        verify(mail,times(1)).send(eq("owner@example.test"),anyString(),contains("https://www.wanted.co.kr/wd/1"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM job_alert_deliveries WHERE state='SENT'",Integer.class)).isEqualTo(1);
    }
    @Test void uncertainSmtpOutcomeIsNotBlindlyRetried() {
        posting("Example",JobPostingStatus.OPEN,clock.instant().minusSeconds(30));
        doThrow(new IllegalStateException("SMTP timeout")).when(mail).send(anyString(),anyString(),anyString());
        assertThat(alerts.run().get("unknownEmails")).isEqualTo(1);
        makeDue(); alerts.run();
        verify(mail,times(1)).send(anyString(),anyString(),anyString());
        assertThat(db.queryForObject("SELECT state FROM job_alert_deliveries",String.class)).isEqualTo("UNKNOWN");
    }
    @Test void unverifiedOrDisabledUsersNeverReceiveRecommendations() {
        posting("Example",JobPostingStatus.OPEN,clock.instant().minusSeconds(30));
        db.update("UPDATE job_alert_subscriptions SET verified=FALSE"); alerts.run();
        db.update("UPDATE job_alert_subscriptions SET verified=TRUE,enabled=FALSE"); alerts.run();
        verify(mail,never()).send(anyString(),anyString(),anyString());
    }
    @Test void verificationRequiresCorrectCodeAndIsSingleUseAndRateLimited() {
        alerts.requestVerification("new@example.test");
        assertThat(alerts.mine().get("verified")).isEqualTo(false);
        var body=ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("new@example.test"),anyString(),body.capture());
        var match=Pattern.compile("[0-9]{6}").matcher(body.getValue()); assertThat(match.find()).isTrue();
        assertThat(alerts.verify(match.group())).isTrue();
        assertThat(alerts.verify(match.group())).isFalse();
        assertThatThrownBy(()->alerts.requestVerification("again@example.test")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void badVerificationAttemptsArePersistedAndCannotEnableDelivery() {
        alerts.requestVerification("new@example.test");
        db.update("UPDATE job_alert_subscriptions SET verification_hash='invalid'");
        for(int i=0;i<8;i++) assertThat(alerts.verify("000000")).isFalse();
        assertThat(db.queryForObject("SELECT verification_attempts FROM job_alert_subscriptions",Integer.class)).isEqualTo(5);
        assertThatThrownBy(()->alerts.save(new AlertController.Settings(true,List.of("Example"),BigDecimal.ZERO)))
                .isInstanceOf(ResponseStatusException.class);
    }
    @Test void companyNamesDropLegalFormsLikeCollectedPostings() {
        alerts.save(new AlertController.Settings(true,List.of("(주)Example","Example 주식회사","㈜ Other Co"," "),BigDecimal.ZERO));
        assertThat(alerts.mine().get("companies")).isEqualTo(List.of("Example","Other Co"));
        assertThat(alerts.targets()).containsExactly("Example","Other Co");
    }
    @Test void recipientDailyLimitIsSharedAcrossAccountsAndCaseVariants() {
        // Recipient limits are independent of the company group expansion.
        for(int i=0;i<5;i++) {
            String identity="quota-"+i+"@onfit.invalid";
            db.update("INSERT INTO app_users(email,display_name) VALUES(?,'Fixture')",identity);
            when(current.identity()).thenReturn(identity);
            alerts.requestVerification(i%2==0?"Owner@Example.test":"owner@example.test");
        }
        when(current.identity()).thenReturn("alerts.owner@onfit.invalid");
        assertThatThrownBy(()->alerts.requestVerification("owner@EXAMPLE.TEST"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error->assertThat(error.getStatusCode().value()).isEqualTo(429));
        verify(mail,times(5)).send(anyString(),anyString(),anyString());
        assertThat(db.queryForObject("SELECT attempts FROM verification_mail_limits",Integer.class)).isEqualTo(5);
        // A quota rejection preserves the owner's verified email and active subscription.
        assertThat(alerts.mine().get("verified")).isEqualTo(true);
        assertThat(alerts.mine().get("enabled")).isEqualTo(true);
    }
    @Test void kakaoInterestIncludesReviewedSubsidiariesInTargetsAndEmails() {
        alerts.save(new AlertController.Settings(true,List.of("카카오","카카오페이"),BigDecimal.ZERO));
        assertThat(alerts.mine().get("companies")).isEqualTo(List.of("카카오","카카오페이"));
        assertThat(alerts.mine().get("expandedCompanies")).isEqualTo(List.of("카카오","카카오페이","카카오뱅크","카카오모빌리티"));
        assertThat(alerts.targets()).containsExactlyInAnyOrder("카카오","카카오페이","카카오뱅크","카카오모빌리티");
        posting("카카오",JobPostingStatus.OPEN,clock.instant().minusSeconds(30));
        posting("카카오페이",JobPostingStatus.OPEN,clock.instant().minusSeconds(30));
        posting("카카오뱅크",JobPostingStatus.OPEN,clock.instant().minusSeconds(30));
        posting("카카오 모빌리티",JobPostingStatus.OPEN,clock.instant().minusSeconds(30));
        posting("카카오와 일하는 개발회사",JobPostingStatus.OPEN,clock.instant().minusSeconds(30));
        assertThat(alerts.run().get("sentEmails")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM job_alert_deliveries WHERE state='SENT'",Integer.class)).isEqualTo(4);
        var body=ArgumentCaptor.forClass(String.class);
        verify(mail).send(anyString(),anyString(),body.capture());
        assertThat(body.getValue()).contains("카카오페이","카카오뱅크","카카오 모빌리티").doesNotContain("카카오와 일하는 개발회사");
    }
    @Test void specificSubsidiaryInterestDoesNotExpandBackToEntireGroup() {
        alerts.save(new AlertController.Settings(true,List.of("카카오페이"),BigDecimal.ZERO));
        assertThat(alerts.targets()).containsExactly("카카오페이");
        posting("카카오",JobPostingStatus.OPEN,clock.instant().minusSeconds(30));
        posting("카카오페이",JobPostingStatus.OPEN,clock.instant().minusSeconds(30));
        posting("카카오뱅크",JobPostingStatus.OPEN,clock.instant().minusSeconds(30));
        assertThat(alerts.run().get("sentEmails")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM job_alert_deliveries",Integer.class)).isEqualTo(1);
    }
    @Test void uncertainVerificationSendFailureStillConsumesRecipientQuota() {
        doThrow(new IllegalStateException("SMTP timeout")).when(mail).send(anyString(),anyString(),anyString());
        for(int i=0;i<5;i++)
            assertThatThrownBy(()->alerts.requestVerification("failure@example.test"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error->assertThat(error.getStatusCode().value()).isEqualTo(502));
        assertThatThrownBy(()->alerts.requestVerification("failure@example.test"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error->assertThat(error.getStatusCode().value()).isEqualTo(429));
        verify(mail,times(5)).send(anyString(),anyString(),anyString());
    }
    @Test void recipientQuotaResetsAtKoreanMidnightRatherThanUtcMidnight() {
        var before=new VerificationMailQuota(db,Clock.fixed(Instant.parse("2026-10-11T14:59:59Z"),ZoneOffset.UTC),transactions);
        for(int i=0;i<5;i++) assertThat(before.reserve("boundary@example.test")).isTrue();
        assertThat(before.reserve("boundary@example.test")).isFalse();
        var after=new VerificationMailQuota(db,Clock.fixed(Instant.parse("2026-10-11T15:00:00Z"),ZoneOffset.UTC),transactions);
        for(int i=0;i<5;i++) assertThat(after.reserve("boundary@example.test")).isTrue();
        assertThat(after.reserve("boundary@example.test")).isFalse();
        assertThat(db.queryForList("SELECT attempts FROM verification_mail_limits ORDER BY limit_day",Integer.class))
                .containsExactly(5,5);
    }
    @Test void concurrentRecipientRequestsCannotReserveMoreThanFiveSlots() throws Exception {
        var quota=new VerificationMailQuota(db,clock,transactions);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(6)) {
            List<java.util.concurrent.Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<12;i++) results.add(executor.submit(()->{
                start.await(); return quota.reserve("concurrent@example.test");
            }));
            start.countDown();
            int allowed=0;
            for(var result:results) if(result.get(15,java.util.concurrent.TimeUnit.SECONDS)) allowed++;
            assertThat(allowed).isEqualTo(5);
        }
        assertThat(db.queryForObject("SELECT attempts FROM verification_mail_limits",Integer.class)).isEqualTo(5);
    }
    @Test void unsubscribeCancelsQueuedItemsAndOwnerIsolationHidesSettings() {
        JobPosting job=posting("Example",JobPostingStatus.OPEN,clock.instant().minusSeconds(30));
        db.update("INSERT INTO job_alert_deliveries(user_id,job_id,state,score) VALUES(?,?,'PENDING',50)",user,job.getId());
        alerts.save(new AlertController.Settings(false,List.of("Example"),BigDecimal.ZERO));
        assertThat(db.queryForObject("SELECT state FROM job_alert_deliveries",String.class)).isEqualTo("CANCELLED");
        when(current.identity()).thenReturn("another@onfit.invalid");
        assertThatThrownBy(()->alerts.mine()).isInstanceOf(ResponseStatusException.class);
    }
}
