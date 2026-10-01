package com.storagehub.security;

import java.util.regex.Pattern;

public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;
    public static final String REGEX =
        "^(?=.*\\p{Ll})(?=.*\\p{Lu})(?=.*\\p{Nd})(?=.*[^\\p{L}\\p{N}\\s])\\S+$";
    public static final String MESSAGE =
        "Password must be 8-128 characters and include uppercase, lowercase, number, special character, and no whitespace";

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private PasswordPolicy() {
    }

    public static boolean isValid(String password) {
        return password != null
            && password.length() >= MIN_LENGTH
            && password.length() <= MAX_LENGTH
            && PATTERN.matcher(password).matches();
    }
}
