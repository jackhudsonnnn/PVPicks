package com.p2picks.app.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Opaque keyset cursors: the sort key of a page's last row, joined with {@code |} and base64url-encoded. Parts must
 * not contain {@code |} (timestamps, UUIDs, numbers and usernames don't).
 */
public final class PageCursor {
    private PageCursor() {}

    public static String encode(Object... parts) {
        StringBuilder joined = new StringBuilder();
        for (Object part : parts) {
            if (!joined.isEmpty()) joined.append('|');
            joined.append(part);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(joined.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The cursor's parts, or IllegalArgumentException when it isn't one of ours with {@code count} parts. */
    public static List<String> decode(String cursor, int count) {
        if (cursor == null || cursor.length() > 512) throw new IllegalArgumentException("Invalid cursor");
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != count) throw new IllegalArgumentException("Invalid cursor");
        return List.of(parts);
    }
}
