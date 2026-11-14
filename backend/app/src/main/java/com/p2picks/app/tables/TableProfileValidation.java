package com.p2picks.app.tables;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.p2picks.app.common.PostgresText;

final class TableProfileValidation {
    /** Table names are 1–14 Unicode code points after trimming, so they fit a table card; the database checks it too. */
    static final int NAME_MAX = 14;

    private TableProfileValidation() {}

    static boolean validName(String name) {
        return !name.isEmpty() && PostgresText.valid(name) && name.codePointCount(0, name.length()) <= NAME_MAX;
    }

    static boolean validEmoji(String emoji) {
        return UCharacter.hasBinaryProperty(emoji, UProperty.RGI_EMOJI);
    }
}
