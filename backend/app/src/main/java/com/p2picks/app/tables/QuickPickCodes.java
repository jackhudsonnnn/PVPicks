package com.p2picks.app.tables;

import java.security.SecureRandom;

final class QuickPickCodes {
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private QuickPickCodes() {}

    static String generate() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return code.toString();
    }
}
