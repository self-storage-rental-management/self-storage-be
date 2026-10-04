package com.storagehub.security;

public final class PasswordPolicy {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
        // Utility class
    }

    public static boolean isValid(String password) {
        if (password == null) {
            return false;
        }

        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return false;
        }

        boolean hasUppercase = false;
        boolean hasLowercase = false;
        boolean hasDigit = false;
        boolean hasSpecialCharacter = false;

        for (char c : password.toCharArray()) {

            if (Character.isWhitespace(c)) {
                return false;
            }

            if (Character.isUpperCase(c)) {
                hasUppercase = true;
            } else if (Character.isLowerCase(c)) {
                hasLowercase = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else {
                hasSpecialCharacter = true;
            }
        }

        return hasUppercase
                && hasLowercase
                && hasDigit
                && hasSpecialCharacter;
    }
}