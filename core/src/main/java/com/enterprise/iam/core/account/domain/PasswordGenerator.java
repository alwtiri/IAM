package com.enterprise.iam.core.account.domain;

import java.security.SecureRandom;

/**
 * Generates rotation passwords: 24 characters with upper case, lower case, digits and symbols, which satisfies Windows/AD
 * complexity and typical Linux/PostgreSQL policies. Symbols avoid quotes, backslashes, colons and whitespace so no
 * target-side parser can misread them. About 150 bits of entropy.
 */
public final class PasswordGenerator {

    static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    static final String DIGITS = "23456789";
    static final String SYMBOLS = "-_.!@#%+=^*";
    private static final String ALL = UPPER + LOWER + DIGITS + SYMBOLS;
    public static final int LENGTH = 24;

    private final SecureRandom random;

    public PasswordGenerator(SecureRandom random) {
        this.random = random;
    }

    /** The caller owns the returned array and must clear it after use. */
    public char[] next() {
        char[] out = new char[LENGTH];
        out[0] = pick(UPPER);
        out[1] = pick(LOWER);
        out[2] = pick(DIGITS);
        out[3] = pick(SYMBOLS);
        for (int i = 4; i < LENGTH; i++) {
            out[i] = pick(ALL);
        }
        for (int i = LENGTH - 1; i > 0; i--) { // Fisher-Yates so the required classes are not at fixed positions
            int j = random.nextInt(i + 1);
            char t = out[i];
            out[i] = out[j];
            out[j] = t;
        }
        return out;
    }

    private char pick(String set) {
        return set.charAt(random.nextInt(set.length()));
    }
}
