package com.bdic.crypto;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Properties;

/**
 * Client key manager.
 *
 * <p>Each user has stable local DES keys and PEKS search key pairs. After the client restarts,
 * it can still decrypt old documents and generate trapdoors that match the current PEKS indexes.</p>
 */
public class ClientKeyManager {

    /** Directory containing local key files, stored by default in a hidden folder under the user home directory. */
    private final Path keyDirectory;

    /**
     * Constructs a manager with the default client key directory.
     *
     * <p>The default path is under the user home directory so historical keys remain available after packaging or changing working directories.</p>
     */
    public ClientKeyManager() {
        this(Paths.get(System.getProperty("user.home"), ".searchable-encryption", "client-keys"));
    }

    /**
     * Constructs a manager with a specified directory, mainly for tests or custom client key storage locations.
     */
    public ClientKeyManager(Path keyDirectory) {
        this.keyDirectory = keyDirectory;
    }

    /**
     * Loads local keys for the specified user; creates and saves them automatically when missing.
     */
    public KeyBundle loadOrCreate(String username) {
        try {
            // Ensure the directory exists first, then migrate keys from the old project directory.
            Files.createDirectories(keyDirectory);
            Path keyFile = keyDirectory.resolve(username + ".properties");
            migrateLegacyKeyFile(username, keyFile);
            if (Files.exists(keyFile)) {
                return load(keyFile);
            }

            // First-time users get a new key bundle written to local files for reuse in later sessions.
            SecretKey desKey = DESUtil.generateKey();
            KeyPair peksKeyPair = PEKSUtil.generateKeyPair();
            save(keyFile, desKey, peksKeyPair.getPublic(), peksKeyPair.getPrivate());
            return new KeyBundle(desKey, peksKeyPair.getPublic(), peksKeyPair.getPrivate());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load client keys", e);
        }
    }

    /**
     * Supports the legacy client-keys folder under the project directory by automatically migrating it to the user directory.
     */
    private void migrateLegacyKeyFile(String username, Path keyFile) throws IOException {
        if (Files.exists(keyFile)) {
            return;
        }

        // Older versions stored keys under project-directory client-keys; copy them to the new location when found.
        Path legacyDirectory = Paths.get("client-keys");
        Path legacyKeyFile = legacyDirectory.resolve(username + ".properties");
        if (!Files.exists(legacyKeyFile)) {
            return;
        }

        Files.createDirectories(keyDirectory);
        Files.copy(legacyKeyFile, keyFile);
    }

    /**
     * Restores DES keys and PEKS search key pairs from a properties file.
     */
    private KeyBundle load(Path keyFile) throws Exception {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(keyFile)) {
            properties.load(inputStream);
        }

        // The properties file stores Base64 text; after loading, convert it back to key objects.
        byte[] desBytes = Base64.getDecoder().decode(properties.getProperty("desKey"));
        SecretKey desKey = DESUtil.getKeyFromBytes(desBytes);
        String publicKeyValue = properties.getProperty("peksPublicKey");
        String privateKeyValue = properties.getProperty("peksPrivateKey");
        if (publicKeyValue == null || privateKeyValue == null) {
            // Older versions stored only an HMAC search key that cannot be split into PEKS keys; generate a new key pair and overwrite the local key file.
            KeyPair peksKeyPair = PEKSUtil.generateKeyPair();
            save(keyFile, desKey, peksKeyPair.getPublic(), peksKeyPair.getPrivate());
            return new KeyBundle(desKey, peksKeyPair.getPublic(), peksKeyPair.getPrivate());
        }

        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyValue);
            byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyValue);
            return new KeyBundle(
                    desKey,
                    PEKSUtil.getPublicKeyFromBytes(publicKeyBytes),
                    PEKSUtil.getPrivateKeyFromBytes(privateKeyBytes)
            );
        } catch (IllegalArgumentException | GeneralSecurityException incompatibleSearchKey) {
            // This may come from an old JPBC experimental search key; keep the DES key and replace the search key with the current RSA PEKS key.
            KeyPair peksKeyPair = PEKSUtil.generateKeyPair();
            save(keyFile, desKey, peksKeyPair.getPublic(), peksKeyPair.getPrivate());
            return new KeyBundle(desKey, peksKeyPair.getPublic(), peksKeyPair.getPrivate());
        }
    }

    /**
     * Saves keys to a local properties file as Base64 text.
     */
    private void save(Path keyFile, SecretKey desKey, PublicKey peksPublicKey, PrivateKey peksPrivateKey) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("desKey", Base64.getEncoder().encodeToString(desKey.getEncoded()));
        properties.setProperty("peksPublicKey", Base64.getEncoder().encodeToString(peksPublicKey.getEncoded()));
        properties.setProperty("peksPrivateKey", Base64.getEncoder().encodeToString(peksPrivateKey.getEncoded()));

        // Use the properties format for easier human inspection while avoiding direct binary output.
        try (OutputStream outputStream = Files.newOutputStream(keyFile)) {
            properties.store(outputStream, null);
        }
    }

    /**
     * Client key bundle for the current user.
     *
     * @param desKey DES key used to encrypt and decrypt document content and keyword metadata.
     * @param peksPublicKey search public key used to generate keyword PEKS ciphertext.
     * @param peksPrivateKey search private key used to generate search trapdoors.
     */
    public record KeyBundle(SecretKey desKey, PublicKey peksPublicKey, PrivateKey peksPrivateKey) {
    }
}
