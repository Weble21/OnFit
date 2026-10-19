-- Independent of user accounts: deleting/replacing an account does not reset this quota.
CREATE TABLE verification_mail_limits (
    email_hash VARCHAR(64) NOT NULL,
    limit_day DATE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    PRIMARY KEY (email_hash, limit_day)
);
