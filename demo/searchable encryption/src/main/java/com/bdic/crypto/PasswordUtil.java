package com.bdic.crypto;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * User password handling utility.
 *
 * <p>The server does not store plaintext passwords; it stores only random salts and PBKDF2-derived password hashes.</p>
 */
public class PasswordUtil {

    /** PBKDF2 iteration count; higher values increase brute-force cost. */
    private static final int ITERATIONS = 65536;
    /** Derived password hash length in bits. */
    private static final int KEY_LENGTH = 256;
    /** Random salt length in bytes. */
    private static final int SALT_LENGTH = 16;

    /**
     * Generates an independent random salt for each user.
     */
    public static byte[] generateSalt() {
        // Generate an independent salt for each registration so identical passwords do not produce identical database hashes.
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /**
     * Generates a salted password hash with PBKDF2WithHmacSHA256.
     */
    public static byte[] hashPassword(String password, byte[] salt) {
        try {
            // PBEKeySpec receives the password char array, salt, iteration count, and output length; the factory performs key derivation.
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate password hash", e);
        }
    }

    /**
     * Checks whether the user-entered password matches the database salt and hash.
     */
    public static boolean matches(String password, byte[] salt, byte[] expectedHash) {
        // Recompute the hash with the same salt and compare it with the stored database hash.
        byte[] actualHash = hashPassword(password, salt);
        return Arrays.equals(actualHash, expectedHash);
    }
}
