package com.sandeep.eventrabackend.model;

import java.util.Locale;
import java.util.regex.Pattern;

public final class UsernamePolicy {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 50;
    public static final String VALIDATION_MESSAGE =
            "Username must be 3 to 50 ASCII letters, digits, or underscores";

    private static final Pattern VALID_USERNAME =
            Pattern.compile("[A-Za-z0-9_]{3,50}");
    private static final Pattern DISALLOWED_USERNAME_CHARACTERS =
            Pattern.compile("[^A-Za-z0-9_]+");

    private UsernamePolicy() {
    }

    /**
     * Applies the boundary rule from Java {@link String#trim()}: leading and
     * trailing UTF-16 code units U+0000 through U+0020 are removed.
     */
    public static String trim(String username) {
        return username.trim();
    }

    public static boolean isValid(String username) {
        return VALID_USERNAME.matcher(username).matches();
    }

    public static String canonicalize(String username) {
        if (username == null) {
            throw new IllegalArgumentException("Username is required");
        }

        String trimmed = trim(username);
        if (!isValid(trimmed)) {
            throw new IllegalArgumentException(VALIDATION_MESSAGE);
        }
        return trimmed;
    }

    public static String normalizeKey(String username) {
        return canonicalize(username).toLowerCase(Locale.ROOT);
    }

    public static String generatedBase(String source) {
        String sanitized = DISALLOWED_USERNAME_CHARACTERS
                .matcher(source)
                .replaceAll("_")
                .toLowerCase(Locale.ROOT);

        if (sanitized.length() > MAX_LENGTH) {
            sanitized = sanitized.substring(0, MAX_LENGTH);
        }

        return sanitized + "_".repeat(Math.max(0, MIN_LENGTH - sanitized.length()));
    }

    public static String withNumericSuffix(String base, int counter) {
        String suffix = Integer.toString(counter);
        int baseLength = Math.min(base.length(), MAX_LENGTH - suffix.length());
        return base.substring(0, baseLength) + suffix;
    }
}
