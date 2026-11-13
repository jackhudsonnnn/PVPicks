package com.p2picks.app.tables;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.p2picks.app.common.PostgresText;

final class TableProfileValidation {
    private TableProfileValidation() {}

    static boolean validName(String name) {
        return !name.isEmpty() && PostgresText.valid(name) && name.codePointCount(0, name.length()) <= 40;
    }

    static boolean validEmoji(String emoji) {
        return UCharacter.hasBinaryProperty(emoji, UProperty.RGI_EMOJI);
    }
}
