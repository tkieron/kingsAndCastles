package pl.kingscastles.server.room;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Return codes: how a child gets back to their kingdom on another device. Eight characters from 32 letters and
 * digits that are easy to read and type (no 0/O, 1/I/L), so 2^40 possible codes. Shown as two groups,
 * {@code K7MQ-4QX2}; the dash, spaces and small letters are ignored when typed in.
 * Guessing is also limited per room (see {@link RoomService#resume}).
 */
public final class ResumeCodes {
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 8;

    private ResumeCodes() {
    }

    static String create(SecureRandom random) {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** What the child typed, without dashes and spaces and in capitals; null if it cannot be a return code. */
    static String normalize(String typed) {
        if (typed == null || typed.length() > 32) {
            return null;
        }
        String s = typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (s.length() != LENGTH) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (ALPHABET.indexOf(s.charAt(i)) < 0) {
                return null;
            }
        }
        return s;
    }

    /** Compares in constant time, so the answer time does not tell how many letters were right. */
    static boolean same(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
