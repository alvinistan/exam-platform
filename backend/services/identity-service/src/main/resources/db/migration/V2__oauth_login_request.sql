-- identity-service · Phase 5
-- A Google sign-in in progress. The browser holds the state in a short-lived cookie; the PKCE verifier and the
-- nonce never leave the server (ADR-004). Rows are single-use and expire after 10 minutes.
CREATE TABLE oauth_login_request (
    state_hash     bytea       PRIMARY KEY,                  -- SHA-256 of the state value
    code_verifier  text        NOT NULL CHECK (length(code_verifier) BETWEEN 43 AND 128),
    nonce          text        NOT NULL,
    return_to      text        NOT NULL CHECK (return_to LIKE '/%' AND return_to NOT LIKE '//%'),
    created_at     timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX oauth_login_request_expiry_idx ON oauth_login_request (expires_at);

-- Lets admin search and the "last administrator" check stay fast as the user base grows.
CREATE INDEX user_role_role_idx ON user_role (role);
