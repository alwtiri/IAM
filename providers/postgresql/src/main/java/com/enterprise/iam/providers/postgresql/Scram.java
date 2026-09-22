package com.enterprise.iam.providers.postgresql;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SCRAM-SHA-256 password verifier in PostgreSQL's stored format (RFC 5802 / RFC 7677):
 * {@code SCRAM-SHA-256$<iterations>:<salt>$<StoredKey>:<ServerKey>}. The password is normalised with NFKC (a subset
 * of SASLprep that covers generated passwords) and its bytes are cleared after use.
 */
final class Scram {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Scram() {
    }

    static String verifier(char[] password, int iterations) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return verifier(password, salt, iterations);
    }

    static String verifier(char[] password, byte[] salt, int iterations) {
        String normalized = Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFKC);
        ByteBuffer bb = StandardCharsets.UTF_8.encode(normalized);
        byte[] pw = new byte[bb.remaining()];
        bb.get(pw);
        byte[] salted = null;
        try {
            salted = hi(pw, salt, iterations);
            byte[] clientKey = hmac(salted, "Client Key".getBytes(StandardCharsets.US_ASCII));
            byte[] storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey);
            byte[] serverKey = hmac(salted, "Server Key".getBytes(StandardCharsets.US_ASCII));
            Base64.Encoder b64 = Base64.getEncoder();
            return "SCRAM-SHA-256$" + iterations + ":" + b64.encodeToString(salt) + "$" + b64.encodeToString(storedKey) + ":"
                    + b64.encodeToString(serverKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SCRAM-SHA-256 is not available", e);
        } finally {
            Arrays.fill(pw, (byte) 0);
            if (bb.hasArray()) {
                Arrays.fill(bb.array(), (byte) 0);
            }
            if (salted != null) {
                Arrays.fill(salted, (byte) 0);
            }
        }
    }

    /** PBKDF2-HMAC-SHA-256 with a single output block (RFC 5802 Hi()). */
    private static byte[] hi(byte[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        mac.update(salt);
        byte[] u = mac.doFinal(new byte[] {0, 0, 0, 1});
        byte[] result = u.clone();
        for (int i = 1; i < iterations; i++) {
            u = mac.doFinal(u);
            for (int j = 0; j < result.length; j++) {
                result[j] ^= u[j];
            }
        }
        return result;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
