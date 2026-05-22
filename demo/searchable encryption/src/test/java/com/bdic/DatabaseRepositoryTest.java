package com.bdic;

import com.bdic.crypto.PEKSUtil;
import com.bdic.db.DatabaseManager;
import com.bdic.db.EncryptedDataRepository;
import com.bdic.model.EncryptedData;
import com.mysql.cj.jdbc.AbandonedConnectionCleanupThread;
import junit.framework.TestCase;

import java.security.KeyPair;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit/integration tests for the encrypted document repository.
 *
 * <p>ID generation tests that do not depend on a database always run; real database round-trip tests must be explicitly enabled
 * {@code -Dse.integration.db=true}。</p>
 */
public class DatabaseRepositoryTest extends TestCase {

    /**
     * Verifies that the same display docId produces different internal storage IDs for different users.
     */
    public void testStorageDocumentIdsAreScopedByUser() {
        String aliceDoc = EncryptedDataRepository.toStorageDocId("alice", "shared-doc");
        String bobDoc = EncryptedDataRepository.toStorageDocId("bob", "shared-doc");

        assertFalse(aliceDoc.equals(bobDoc));
        assertTrue(aliceDoc.startsWith("doc-"));
        assertEquals(aliceDoc, EncryptedDataRepository.toStorageDocId("alice", "shared-doc"));
    }

    /**
     * When database integration tests are enabled, verifies save, search, list, and lightweight binary search results.
     */
    public void testRepositoryRoundTripWhenDatabaseIntegrationIsEnabled() throws Exception {
        if (!Boolean.getBoolean("se.integration.db")) {
            // Skip by default so ordinary unit tests do not require local MySQL.
            return;
        }

        // Database connection parameters can be overridden with system properties and default to the local test database.
        String host = System.getProperty("se.db.host", "localhost");
        int port = Integer.parseInt(System.getProperty("se.db.port", "3306"));
        String databaseName = System.getProperty("se.db.name", "searchable_encryption_test");
        String username = System.getProperty("se.db.user", "root");
        String password = System.getProperty("se.db.password", "123456ysy");
        String ownerUsername = "repository_test_user";

        try {
            // Initialize the test database and repository objects, then clean up data left from the previous test.
            DatabaseManager databaseManager = new DatabaseManager(host, port, databaseName, username, password);
            databaseManager.initialize();
            EncryptedDataRepository repository = new EncryptedDataRepository(databaseManager);
            repository.deleteByOwnerAndDocId(ownerUsername, "doc-1");
            repository.deleteByOwnerAndDocId(ownerUsername, "doc-2");
            repository.deleteByOwnerAndDocId(ownerUsername, "doc-3");

            try (Connection connection = databaseManager.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM users WHERE username = '" + ownerUsername + "'");
                statement.executeUpdate(
                        "INSERT INTO users (username, password_hash, password_salt) VALUES ('" + ownerUsername + "', X'01', X'01')"
                );
            }

            KeyPair peksKeyPair = PEKSUtil.generateKeyPair();

            // Construct three test documents: two text documents and one image-like binary document.
            EncryptedData firstDocument = new EncryptedData(
                    "doc-1",
                    "encrypted-1".getBytes(),
                    encryptKeywords(peksKeyPair.getPublic(), "alpha", "beta")
            );

            EncryptedData secondDocument = new EncryptedData(
                    "doc-2",
                    "encrypted-2".getBytes(),
                    encryptKeywords(peksKeyPair.getPublic(), "gamma")
            );

            EncryptedData binaryDocument = new EncryptedData(
                    "doc-3",
                    "screenshot.png",
                    "image/png",
                    "image",
                    3,
                    null,
                    new byte[]{0x01, 0x02, 0x03},
                    encryptKeywords(peksKeyPair.getPublic(), "screenshot")
            );

            repository.save(ownerUsername, firstDocument);
            repository.save(ownerUsername, secondDocument);
            repository.save(ownerUsername, binaryDocument);

            // Verify full-keyword, prefix-keyword, and image-keyword search separately.
            byte[] trapdoor = PEKSUtil.getTrapdoor(peksKeyPair.getPrivate(), "alpha");
            List<EncryptedData> searchResults = repository.searchByTrapdoor(ownerUsername, trapdoor);
            byte[] prefixTrapdoor = PEKSUtil.getTrapdoor(peksKeyPair.getPrivate(), "alp");
            List<EncryptedData> prefixSearchResults = repository.searchByTrapdoor(ownerUsername, prefixTrapdoor);
            byte[] imageTrapdoor = PEKSUtil.getTrapdoor(peksKeyPair.getPrivate(), "screenshot");
            List<EncryptedData> imageSearchResults = repository.searchByTrapdoor(ownerUsername, imageTrapdoor);

            assertEquals(1, searchResults.size());
            assertEquals("doc-1", searchResults.get(0).getDocId());
            assertEquals(1, prefixSearchResults.size());
            assertEquals("doc-1", prefixSearchResults.get(0).getDocId());
            assertEquals(1, imageSearchResults.size());
            assertEquals("doc-3", imageSearchResults.get(0).getDocId());
            // Image search results omit full encryptedContent to save network overhead.
            assertNull(imageSearchResults.get(0).getEncryptedContent());
        } finally {
            // The MySQL driver starts a cleanup thread; close it explicitly after tests to avoid a hanging process.
            AbandonedConnectionCleanupThread.checkedShutdown();
        }
    }

    /**
     * Generates keyword ciphertext using the same prefix expansion rules as production code.
     */
    private static List<byte[]> encryptKeywords(PublicKey peksPublicKey, String... keywords) throws Exception {
        Set<String> tokens = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }

            String normalizedKeyword = keyword.trim().toLowerCase();
            tokens.add(normalizedKeyword);
            if (normalizedKeyword.length() <= 2) {
                continue;
            }

            // Support prefix search: alpha additionally generates al, alp, and alph.
            for (int i = 2; i < normalizedKeyword.length(); i++) {
                tokens.add(normalizedKeyword.substring(0, i));
            }
        }

        List<byte[]> encryptedKeywords = new ArrayList<>();
        for (String token : tokens) {
            // Server search tests these PEKS ciphertexts with the query trapdoor.
            encryptedKeywords.add(PEKSUtil.encrypt(peksPublicKey, token));
        }
        return encryptedKeywords;
    }
}
