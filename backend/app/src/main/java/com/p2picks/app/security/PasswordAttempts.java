package com.p2picks.app.security;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The sign-in throttle for other modules' password checks (deleting an account). Failures count per account and per
 * address against the same budgets as sign-in and changing your password; over either, the check is refused for the
 * throttle's window even with the right password.
 */
@Component
public class PasswordAttempts {
    private final LoginThrottle throttle;

    PasswordAttempts(LoginThrottle throttle) {
        this.throttle = throttle;
    }

    /** True when this account or address has failed too often; refuse with 429 and {@link #window()}. */
    public boolean blocked(String account, String address) {
        return throttle.blocked(account, address);
    }

    public void failed(String account, String address) {
        throttle.failed(account, address);
    }

    public void succeeded(String account) {
        throttle.succeeded(account);
    }

    public Duration window() {
        return throttle.window();
    }
}
