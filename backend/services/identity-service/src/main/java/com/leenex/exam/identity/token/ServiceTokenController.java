package com.leenex.exam.identity.token;

import com.leenex.exam.identity.config.IdentityProperties;
import com.leenex.exam.identity.config.IdentityProperties.ServiceClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 2.0 client-credentials grant for backend services (ADR-005, RFC 6749 §4.4). Clients authenticate with HTTP
 * Basic or with {@code client_id}/{@code client_secret} form fields. Only reachable inside the Docker network: the
 * gateway has no route to {@code /oauth2/**}.
 */
@RestController
public class ServiceTokenController {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenController.class);

    private final IdentityProperties properties;
    private final AccessTokens tokens;

    public ServiceTokenController(IdentityProperties properties, AccessTokens tokens) {
        this.properties = properties;
        this.tokens = tokens;
    }

    @PostMapping(path = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam MultiValueMap<String, String> form) {
        String[] credentials = credentials(authorization, form);
        if (credentials == null) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_client");
        }
        ServiceClient client = properties.activeServiceClients().get(credentials[0]);
        // Compare even for unknown clients, so timing doesn't reveal which IDs exist.
        boolean secretOk = constantTimeEquals(credentials[1], client == null ? "" : client.secret());
        if (client == null || !secretOk) {
            log.warn("Service token refused for client {}", credentials[0].replaceAll("[^\\w.-]", "?"));
            return error(HttpStatus.UNAUTHORIZED, "invalid_client");
        }
        if (!"client_credentials".equals(form.getFirst("grant_type"))) {
            return error(HttpStatus.BAD_REQUEST, "unsupported_grant_type");
        }
        List<String> allowed = client.scopes() == null ? List.of() : client.scopes();
        List<String> granted = allowed;
        String requested = form.getFirst("scope");
        if (requested != null && !requested.isBlank()) {
            granted = List.of(requested.trim().split("\\s+"));
            if (!allowed.containsAll(granted)) {
                return error(HttpStatus.BAD_REQUEST, "invalid_scope");
            }
        }
        AccessTokens.Issued issued = tokens.issueService(credentials[0], granted, client.tokenTtl());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", issued.token());
        body.put("token_type", "Bearer");
        body.put("expires_in", issued.expiresInSeconds());
        body.put("scope", String.join(" ", granted));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }

    /** {client_id, client_secret} from HTTP Basic or the form; null when absent or malformed. */
    private static String[] credentials(String authorization, MultiValueMap<String, String> form) {
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                        StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                return colon <= 0 ? null : new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)};
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        String id = form.getFirst("client_id");
        String secret = form.getFirst("client_secret");
        return id == null || id.isBlank() || secret == null ? null : new String[] {id, secret};
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status == HttpStatus.UNAUTHORIZED) {
            response.header("WWW-Authenticate", "Basic realm=\"service\"");
        }
        return response.body(Map.of("error", code));
    }
}
