package com.leenex.exam.identity.auth;

/**
 * A sign-in that can't complete. The code is shown to the user on the web app's sign-in page
 * ({@code /signin?error=<code>}); the message is for the log only.
 */
public class SignInException extends RuntimeException {

    private final String code;

    public SignInException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SignInException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
