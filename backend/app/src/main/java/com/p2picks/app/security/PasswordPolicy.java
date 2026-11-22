package com.p2picks.app.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Rules for a password a player chooses (NIST SP 800-63B style): 12 to 64 characters and at most 72 bytes in UTF-8
 * (BCrypt's limit), not the username, and not on a bundled list of common passwords. No required character mixes.
 */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 64;
    static final int MAX_BYTES = 72;
    private static final Set<String> COMMON = load();

    private PasswordPolicy() {}

    /** Why the password isn't allowed, as a sentence for the player; empty when it's fine. */
    public static Optional<String> problem(String username, String password) {
        if (password == null) return Optional.of("Choose a password.");
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) return Optional.of("Use at least " + MIN_LENGTH + " characters.");
        if (length > MAX_LENGTH) return Optional.of("Use at most " + MAX_LENGTH + " characters.");
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of("That password is too long; use fewer special characters.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (username != null && lower.contains(username.toLowerCase(Locale.ROOT))) {
            return Optional.of("Don't include your username.");
        }
        if (COMMON.contains(lower)) return Optional.of("That password is too common; choose another.");
        return Optional.empty();
    }

    private static Set<String> load() {
        Set<String> words = new HashSet<>();
        try (InputStream input = Objects.requireNonNull(
                PasswordPolicy.class.getResourceAsStream("/security/common-passwords.txt"), "common-passwords.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.strip().toLowerCase(Locale.ROOT);
                if (!word.isEmpty() && !word.startsWith("#")) words.add(word);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read the common-password list", exception);
        }
        return Set.copyOf(words);
    }
}
