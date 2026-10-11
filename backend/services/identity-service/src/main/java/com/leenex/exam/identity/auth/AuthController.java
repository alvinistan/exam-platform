package com.leenex.exam.identity.auth;

import com.leenex.exam.identity.config.IdentityProperties;
import com.leenex.exam.identity.token.AccessTokens;
import com.leenex.exam.identity.token.RefreshTokens;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import com.leenex.exam.platform.web.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Sign-in with Google and the token lifecycle (contracts/openapi/identity-service.yaml, tag Auth).
 *
 * <p>Browser flow: {@code start} → Google → {@code callback} (sets the refresh cookie) → the web app calls
 * {@code token} for a 15-minute access token, and again whenever it needs a new one.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    /** Cookie-based calls must send this header; a cross-site form can't, which blocks CSRF. */
    static final String CSRF_HEADER = "X-Requested-With";

    private final AuthService auth;
    private final IdentityProperties properties;
    private final RefreshTokens refreshTokens;

    public AuthController(AuthService auth, IdentityProperties properties, RefreshTokens refreshTokens) {
        this.auth = auth;
        this.properties = properties;
        this.refreshTokens = refreshTokens;
    }

    @GetMapping("/google/start")
    public ResponseEntity<Void> start(@RequestParam(required = false) String returnTo) {
        try {
            AuthService.SignInStart start = auth.start(returnTo);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(start.googleUrl())
                    .header(HttpHeaders.SET_COOKIE,
                            AuthCookies.login(start.state(), properties.google().loginTimeout()).toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .build();
        } catch (SignInException e) {
            log.warn("Sign-in could not start: {}", e.getMessage());
            return redirectToSignInPage(e.code());
        }
    }

    @GetMapping("/google/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         HttpServletRequest request) {
        if (error != null) {
            // The user cancelled at Google, or Google refused the request.
            return redirectToSignInPage("access_denied".equals(error) ? "cancelled" : "google_error");
        }
        try {
            AuthService.SignedIn signedIn = auth.complete(code, state,
                    AuthCookies.read(request, AuthCookies.LOGIN).orElse(null), request.getHeader(HttpHeaders.USER_AGENT));
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(appUrl(signedIn.returnTo()))
                    .header(HttpHeaders.SET_COOKIE, AuthCookies.clearLogin().toString())
                    .header(HttpHeaders.SET_COOKIE,
                            AuthCookies.refresh(signedIn.refreshToken().value(), refreshTokens.ttl()).toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .build();
        } catch (SignInException e) {
            log.warn("Sign-in failed ({}): {}", e.code(), e.getMessage());
            return redirectToSignInPage(e.code());
        }
    }

    /** Request body for mobile apps, which keep the refresh token in secure device storage. */
    public record TokenRequest(String refreshToken) {
    }

    public record TokenResponse(String accessToken, long expiresIn, String tokenType, String refreshToken) {
    }

    @PostMapping("/token")
    public ResponseEntity<?> token(@RequestBody(required = false) TokenRequest body,
                                               @RequestHeader(value = CSRF_HEADER, required = false) String requestedWith,
                                               HttpServletRequest request) {
        boolean mobile = body != null && body.refreshToken() != null && !body.refreshToken().isBlank();
        String presented = mobile ? body.refreshToken() : cookieToken(request, requestedWith)
                .orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED, "You're not signed in."));

        AuthService.Refreshed refreshed;
        try {
            refreshed = auth.refresh(presented, request.getHeader(HttpHeaders.USER_AGENT));
        } catch (ApiException e) {
            if (mobile) {
                throw e;
            }
            // The browser's cookie is useless now; clear it so the app shows "signed out" cleanly.
            return ResponseEntity.status(e.code().status())
                    .header(HttpHeaders.SET_COOKIE, AuthCookies.clearRefresh().toString())
                    .body(ProblemResponses.of(e.code(), e.getMessage()));
        }
        AccessTokens.Issued access = refreshed.access();
        String newRefresh = refreshed.newRefreshToken() == null ? null : refreshed.newRefreshToken().value();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (!mobile && newRefresh != null) {
            response.header(HttpHeaders.SET_COOKIE, AuthCookies.refresh(newRefresh, refreshTokens.ttl()).toString());
        }
        return response.body(new TokenResponse(access.token(), access.expiresInSeconds(), "Bearer",
                mobile ? newRefresh : null));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) TokenRequest body,
                                       @RequestHeader(value = CSRF_HEADER, required = false) String requestedWith,
                                       HttpServletRequest request) {
        boolean mobile = body != null && body.refreshToken() != null && !body.refreshToken().isBlank();
        Optional<String> presented = mobile ? Optional.of(body.refreshToken()) : cookieToken(request, requestedWith);
        presented.ifPresent(auth::signOut);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, AuthCookies.clearRefresh().toString())
                .build();
    }

    private Optional<String> cookieToken(HttpServletRequest request, String requestedWith) {
        Optional<String> cookie = AuthCookies.read(request, AuthCookies.REFRESH);
        if (cookie.isPresent() && !"fetch".equals(requestedWith)) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Missing X-Requested-With: fetch header.");
        }
        return cookie;
    }

    private ResponseEntity<Void> redirectToSignInPage(String errorCode) {
        URI target = UriComponentsBuilder.fromUri(appUrl("/signin")).queryParam("error", errorCode).build().toUri();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(target)
                .header(HttpHeaders.SET_COOKIE, AuthCookies.clearLogin().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private URI appUrl(String path) {
        return properties.publicBaseUrl().resolve(path);
    }
}
