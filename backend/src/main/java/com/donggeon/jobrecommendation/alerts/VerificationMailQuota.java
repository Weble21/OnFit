package com.donggeon.jobrecommendation.alerts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Recipient-wide quota, committed before SMTP even when the caller later rolls back. */
@Component
public class VerificationMailQuota {
    private static final ZoneId DAY_ZONE = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate db;
    private final Clock clock;
    private final TransactionTemplate tx;

    public VerificationMailQuota(JdbcTemplate db, Clock clock, PlatformTransactionManager manager) {
        this.db = db;
        this.clock = clock;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean reserve(String email) {
        String key = hash(email.strip().toLowerCase(Locale.ROOT));
        var day = java.sql.Date.valueOf(LocalDate.ofInstant(clock.instant(), DAY_ZONE));
        return Boolean.TRUE.equals(tx.execute(status -> {
            // PostgreSQL and H2 PostgreSQL mode: a racing first request cannot overwrite the count.
            db.update("INSERT INTO verification_mail_limits(email_hash,limit_day,attempts) VALUES(?,?,0) ON CONFLICT DO NOTHING",
                    key, day);
            return db.update("UPDATE verification_mail_limits SET attempts=attempts+1 WHERE email_hash=? AND limit_day=? AND attempts<5",
                    key, day) == 1;
        }));
    }

    private static String hash(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
