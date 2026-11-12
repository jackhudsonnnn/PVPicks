package com.p2picks.app;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;

final class TableProfileValidation {
    private TableProfileValidation() {}

    static boolean validName(String name) {
        return !name.isEmpty() && PostgresText.valid(name) && name.codePointCount(0, name.length()) <= 40;
    }

    static boolean validEmoji(String emoji) {
        return UCharacter.hasBinaryProperty(emoji, UProperty.RGI_EMOJI);
    }
}
