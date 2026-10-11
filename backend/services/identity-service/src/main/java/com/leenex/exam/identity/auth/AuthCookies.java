package com.leenex.exam.identity.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.ResponseCookie;

/**
 * The two cookies identity-service sets. Both use the {@code __Host-} prefix, which makes browsers insist on
 * Secure, Path=/ and no Domain, so another subdomain can never set or read them.
 */
final class AuthCookies {

    /** Holds the refresh token for the web app. Strict: never sent on requests started by another site. */
    static final String REFRESH = "__Host-rt";
    /**
     * Binds a sign-in to the browser that started it (login CSRF defence). Lax, because it must come back on
     * the top-level redirect from Google.
     */
    static final String LOGIN = "__Host-oauth";

    private AuthCookies() {
    }

    static ResponseCookie refresh(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH, value).path("/").secure(true).httpOnly(true).sameSite("Strict")
                .maxAge(maxAge).build();
    }

    static ResponseCookie clearRefresh() {
        return refresh("", Duration.ZERO);
    }

    static ResponseCookie login(String state, Duration maxAge) {
        return ResponseCookie.from(LOGIN, state).path("/").secure(true).httpOnly(true).sameSite("Lax")
                .maxAge(maxAge).build();
    }

    static ResponseCookie clearLogin() {
        return login("", Duration.ZERO);
    }

    static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies).filter(c -> name.equals(c.getName())).map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank()).findFirst();
    }
}
