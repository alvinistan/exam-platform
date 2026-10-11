package com.leenex.exam.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for Google's OpenID Connect endpoints: the token endpoint (checks client secret and PKCE verifier,
 * returns an RS256-signed ID token) and the signing-key endpoint.
 */
public final class FakeGoogle {

    public static final String CLIENT_ID = "test-client.apps.googleusercontent.com";
    public static final String CLIENT_SECRET = "test-secret";
    public static final String ISSUER = "https://accounts.google.com";

    private final RSAKey key;
    private final HttpServer server;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    /** What Google will put in the ID token for an authorization code. */
    public record Grant(String codeChallenge, String subject, String email, boolean emailVerified, String name,
                        String nonce, String audience, String issuer) {
    }

    public FakeGoogle() {
        try {
            key = new RSAKeyGenerator(2048).keyID("google-test").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/certs", ex -> respond(ex, 200, new JWKSet(key.toPublicJWK()).toString()));
        server.createContext("/token", this::token);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void willGrant(String code, Grant grant) {
        grants.put(code, grant);
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> form = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Grant grant = grants.remove(form.getOrDefault("code", ""));
        if (grant == null || !CLIENT_SECRET.equals(form.get("client_secret")) || !CLIENT_ID.equals(form.get("client_id"))
                || !"authorization_code".equals(form.get("grant_type"))) {
            respond(ex, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        if (!grant.codeChallenge().equals(s256(form.getOrDefault("code_verifier", "")))) {
            respond(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"PKCE verification failed\"}");
            return;
        }
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(grant.issuer())
                .audience(grant.audience())
                .subject(grant.subject())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("email", grant.email())
                .claim("email_verified", grant.emailVerified())
                .claim("name", grant.name())
                .claim("picture", "https://example.com/photo.png")
                .claim("nonce", grant.nonce())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            respond(ex, 200, "{\"access_token\":\"ya29.test\",\"token_type\":\"Bearer\",\"expires_in\":3599,\"id_token\":\""
                    + jwt.serialize() + "\"}");
        } catch (JOSEException e) {
            respond(ex, 500, "{}");
        }
    }

    public void stop() {
        server.stop(0);
    }

    static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }
}
