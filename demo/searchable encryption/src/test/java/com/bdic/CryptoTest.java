package com.bdic;

import com.bdic.crypto.DESUtil;
import com.bdic.crypto.PEKSUtil;
import com.bdic.crypto.PasswordUtil;
import junit.framework.TestCase;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

/**
 * Unit tests for encryption-related utilities.
 */
public class CryptoTest extends TestCase {

    /**
     * Verifies that DES-encrypted content can be correctly decrypted back to plaintext with the same key.
     */
    public void testDesRoundTrip() throws Exception {
        // Generate a one-time test key and plaintext.
        SecretKey desKey = DESUtil.generateKey();
        String plaintext = "Hello World Data";

        // Encrypt and then decrypt; the byte content should be fully restored.
        byte[] encrypted = DESUtil.encrypt(plaintext.getBytes(StandardCharsets.UTF_8), desKey);
        byte[] decrypted = DESUtil.decrypt(encrypted, desKey);

        assertEquals(plaintext, new String(decrypted, StandardCharsets.UTF_8));
    }

    /**
     * Verifies that a PEKS trapdoor matches only the same normalized keyword.
     */
    public void testPeksTrapdoorMatchesOnlySameKeyword() throws Exception {
        KeyPair peksKeyPair = PEKSUtil.generateKeyPair();

        // encrypt and getTrapdoor both trim and lowercase, so Secret and secret should match.
        byte[] peksCiphertext = PEKSUtil.encrypt(peksKeyPair.getPublic(), "Secret");
        byte[] trapdoor = PEKSUtil.getTrapdoor(peksKeyPair.getPrivate(), "secret");
        byte[] wrongTrapdoor = PEKSUtil.getTrapdoor(peksKeyPair.getPrivate(), "wrong");

        assertTrue(PEKSUtil.test(peksCiphertext, trapdoor));
        assertFalse(PEKSUtil.test(peksCiphertext, wrongTrapdoor));
    }

    /**
     * Verifies that PEKS public/private keys persisted as bytes can be restored and still complete search matching.
     */
    public void testPeksKeyPairCanBeRestoredFromEncodedBytes() throws Exception {
        KeyPair peksKeyPair = PEKSUtil.generateKeyPair();

        byte[] peksCiphertext = PEKSUtil.encrypt(
                PEKSUtil.getPublicKeyFromBytes(peksKeyPair.getPublic().getEncoded()),
                "restore"
        );
        byte[] trapdoor = PEKSUtil.getTrapdoor(
                PEKSUtil.getPrivateKeyFromBytes(peksKeyPair.getPrivate().getEncoded()),
                "restore"
        );

        assertTrue(PEKSUtil.test(peksCiphertext, trapdoor));
    }

    /**
     * Verifies that password hash checking accepts the correct password and rejects the wrong one.
     */
    public void testPasswordHashVerification() {
        // Registration stores the salt and hash; sign-in recomputes the hash with the same salt.
        byte[] salt = PasswordUtil.generateSalt();
        byte[] hash = PasswordUtil.hashPassword("correct horse battery staple", salt);

        assertTrue(PasswordUtil.matches("correct horse battery staple", salt, hash));
        assertFalse(PasswordUtil.matches("wrong password", salt, hash));
    }
}
