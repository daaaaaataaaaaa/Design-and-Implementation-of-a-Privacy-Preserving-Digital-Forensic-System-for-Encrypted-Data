package com.bdic.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.NoSuchAlgorithmException;

/**
 * DES symmetric encryption utility.
 *
 * <p>This project uses it to encrypt document content and keyword metadata. DES is mainly for coursework/demo scenarios;
 * for real production systems, replace it with a modern authenticated encryption algorithm such as AES-GCM.</p>
 */
public class DESUtil {

    /** Standard DES algorithm name in JCE. */
    private static final String ALGORITHM = "DES";

    /**
     * Generates a new DES key.
     */
    public static SecretKey generateKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
            // DES has a fixed effective key length of 56 bits.
            keyGen.init(56);
            return keyGen.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error generating DES key", e);
        }
    }

    /**
     * Restores a DES key from persisted raw bytes.
     */
    public static SecretKey getKeyFromBytes(byte[] keyBytes) {
        // SecretKeySpec does not rederive the key; it wraps saved raw bytes as a JCE key object.
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * Encrypts plaintext bytes with the specified key.
     */
    public static byte[] encrypt(byte[] plaintext, SecretKey key) throws Exception {
        // Use JCE Cipher for one-shot block encryption; callers provide the complete plaintext bytes.
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(plaintext);
    }

    /**
     * Decrypts ciphertext bytes with the specified key.
     */
    public static byte[] decrypt(byte[] ciphertext, SecretKey key) throws Exception {
        // The decryption flow mirrors encryption and restores the original bytes before upload.
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(ciphertext);
    }
}
