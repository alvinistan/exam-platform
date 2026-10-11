package com.leenex.exam.identity.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Removes abandoned sign-in requests and long-dead refresh tokens. Safe to run on every instance. */
@Component
class Housekeeping {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);

    private final LoginRequests loginRequests;
    private final JdbcClient jdbc;

    Housekeeping(LoginRequests loginRequests, JdbcClient jdbc) {
        this.loginRequests = loginRequests;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void run() {
        int logins = loginRequests.deleteExpired();
        // Kept 30 days after expiry or revocation so reuse of an old token is still recognised and logged.
        int tokens = jdbc.sql("""
                DELETE FROM refresh_token t
                WHERE COALESCE(t.revoked_at, t.expires_at) < now() - interval '30 days'
                  AND NOT EXISTS (SELECT 1 FROM refresh_token n WHERE n.replaced_by = t.id)
                """).update();
        if (logins + tokens > 0) {
            log.info("Housekeeping removed {} expired sign-in requests and {} old refresh tokens", logins, tokens);
        }
    }
}
