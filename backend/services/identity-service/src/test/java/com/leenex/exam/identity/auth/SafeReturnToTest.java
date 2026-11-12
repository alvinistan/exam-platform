package com.leenex.exam.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** After sign-in the browser only ever returns to a path on this site (no open redirect, no broken callback). */
class SafeReturnToTest {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/exams/ol-science", "/account/payments?tab=all", "/exams/a%20b#top"})
    void keepsPathsOnThisSite(String path) {
        assertThat(LoginRequests.safeReturnTo(path)).isEqualTo(path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "//evil.example", "/\\evil.example", "evil", "/a b", "/a\tb",
            "/a%zz", "/x\u007f", ""})
    void sendsAnythingElseHome(String path) {
        assertThat(LoginRequests.safeReturnTo(path)).isEqualTo("/");
    }
}
