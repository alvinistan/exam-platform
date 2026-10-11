package com.leenex.exam.identity.token;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public keys every service uses to verify access tokens. Never contains private key material. */
@RestController
public class JwksController {

    private final SigningKeys keys;

    public JwksController(SigningKeys keys) {
        this.keys = keys;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = "application/json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(keys.publicSet().toJSONObject(true));
    }
}
