package com.leenex.exam.identity.token;

import com.leenex.exam.identity.config.IdentityProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The ES256 keys that sign access tokens. Configured as a JWK set ({@code IDENTITY_JWT_SIGNING_KEYS}); the first
 * key signs new tokens and every key stays published, so rotation is: add a new key first, wait one token
 * lifetime, then remove the old one.
 *
 * <p>Without configured keys (local development only) a temporary key is generated at startup, so tokens issued
 * before a restart stop working; the web app simply refreshes.
 */
@Component
public class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    private final ECKey signingKey;
    private final JWKSet privateSet;
    private final JWKSet publicSet;

    public SigningKeys(IdentityProperties properties) {
        String configured = properties.jwt().signingKeys();
        List<ECKey> keys;
        if (configured != null && !configured.isBlank()) {
            keys = parse(configured);
        } else if (properties.jwt().requireConfiguredKey()) {
            throw new IllegalStateException("identity.jwt.signing-keys (IDENTITY_JWT_SIGNING_KEYS) must be set");
        } else {
            log.warn("No access-token signing key configured; using a temporary key. Never do this outside local development.");
            keys = List.of(generate());
        }
        this.signingKey = keys.getFirst();
        this.privateSet = new JWKSet(new ArrayList<JWK>(keys));
        this.publicSet = new JWKSet(keys.stream().map(k -> (JWK) k.toPublicJWK()).toList());
    }

    public ECKey signingKey() {
        return signingKey;
    }

    /** Includes private parts; only for signing. */
    JWKSet privateSet() {
        return privateSet;
    }

    /** Safe to publish. */
    public JWKSet publicSet() {
        return publicSet;
    }

    private static List<ECKey> parse(String json) {
        try {
            JWKSet set = JWKSet.parse(json);
            List<ECKey> keys = set.getKeys().stream().map(k -> {
                if (!(k instanceof ECKey ec) || !Curve.P_256.equals(ec.getCurve()) || !ec.isPrivate() || ec.getKeyID() == null) {
                    throw new IllegalStateException("Signing keys must be private EC P-256 keys with a kid");
                }
                return ec;
            }).toList();
            if (keys.isEmpty()) {
                throw new IllegalStateException("Signing key set is empty");
            }
            return keys;
        } catch (ParseException e) {
            throw new IllegalStateException("identity.jwt.signing-keys is not a valid JWK set", e);
        }
    }

    /** Also used by the key-generation command in the README. */
    public static ECKey generate() {
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyUse(KeyUse.SIGNATURE)
                    .keyID("k" + Instant.now().getEpochSecond())
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
