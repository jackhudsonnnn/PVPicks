package com.p2picks.app;

final class PostgresText {
    private PostgresText() {}

    static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && jsWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && jsWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    static boolean jsWhitespace(int codePoint) {
        return Character.isSpaceChar(codePoint) || codePoint == 0xFEFF
                || codePoint >= 0x09 && codePoint <= 0x0D;
    }

    static boolean valid(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == 0 || Character.isLowSurrogate(current)) return false;
            if (Character.isHighSurrogate(current)) {
                if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
            }
        }
        return true;
    }
}
