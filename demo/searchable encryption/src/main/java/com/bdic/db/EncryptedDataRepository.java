package com.bdic.db;

import com.bdic.crypto.PEKSUtil;
import com.bdic.model.DocumentSummary;
import com.bdic.model.EncryptedData;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.HexFormat;
import java.util.Set;

/**
 * Encrypted document repository.
 *
 * <p>Saves encrypted content, maintains encrypted keyword indexes, and isolates query results by current user. The public docId
 * is the document ID entered by the user, while the database primary key additionally mixes in the username to prevent users with the same docId from overwriting each other.</p>
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class EncryptedDataRepository {

    /** Database access entry point. */
    private final DatabaseManager databaseManager;

    /** Injects the database manager used by the repository for document and index operations. */
    public EncryptedDataRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Saves or overwrites the current user's document while rebuilding its keyword index.
     */
    public void save(String username, EncryptedData encryptedData) {
        String displayDocId = requireText(encryptedData.getDocId(), "Document ID");
        String storageDocId = toStorageDocId(username, displayDocId);

        // Use MySQL upsert for the document body: insert new documents and overwrite metadata/encrypted content for existing documents.
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String upsertDocumentSql = """
            INSERT INTO documents (doc_id, display_doc_id, owner_username, file_name, mime_type, media_type, file_size, encrypted_keyword_metadata, encrypted_content)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                display_doc_id = VALUES(display_doc_id),
                owner_username = VALUES(owner_username),
                file_name = VALUES(file_name),
                mime_type = VALUES(mime_type),
                media_type = VALUES(media_type),
                file_size = VALUES(file_size),
                encrypted_keyword_metadata = VALUES(encrypted_keyword_metadata),
                encrypted_content = VALUES(encrypted_content)
            """;
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String deleteKeywordsSql = "DELETE FROM keyword_index WHERE doc_id = ?";
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String insertKeywordSql = "INSERT INTO keyword_index (doc_id, peks_ciphertext) VALUES (?, ?)";

        try (Connection connection = databaseManager.getConnection()) {
            // The document body and keyword index must succeed or fail together, so explicitly start a transaction.
            connection.setAutoCommit(false);

            try (PreparedStatement upsertDocument = connection.prepareStatement(upsertDocumentSql);
                 PreparedStatement deleteKeywords = connection.prepareStatement(deleteKeywordsSql);
                 PreparedStatement insertKeyword = connection.prepareStatement(insertKeywordSql)) {
                // Save the document body first, including user-visible ID, file metadata, and encrypted content.
                upsertDocument.setString(1, storageDocId);
                upsertDocument.setString(2, displayDocId);
                upsertDocument.setString(3, username);
                upsertDocument.setString(4, encryptedData.getFileName());
                upsertDocument.setString(5, encryptedData.getMimeType());
                upsertDocument.setString(6, encryptedData.getMediaType());
                upsertDocument.setLong(7, encryptedData.getFileSize());
                upsertDocument.setBytes(8, encryptedData.getEncryptedKeywordMetadata());
                upsertDocument.setBytes(9, encryptedData.getEncryptedContent());
                upsertDocument.executeUpdate();

                // On document update, delete old indexes before writing new keyword ciphertext so search results match the latest content.
                deleteKeywords.setString(1, storageDocId);
                deleteKeywords.executeUpdate();

                List<byte[]> ciphertexts = encryptedData.getPeksCiphertexts() == null
                        ? List.of()
                        : encryptedData.getPeksCiphertexts();
                // There may be many keyword ciphertexts; use batches to reduce database round trips.
                for (byte[] peksCiphertext : ciphertexts) {
                    insertKeyword.setString(1, storageDocId);
                    insertKeyword.setBytes(2, peksCiphertext);
                    insertKeyword.addBatch();
                }
                insertKeyword.executeBatch();

                // Commit after all steps succeed so client search sees a complete and consistent index.
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save encrypted document", e);
        }
    }

    /**
     * Searches encrypted documents accessible to the current user with the trapdoor sent by the client.
     */
    public List<EncryptedData> searchByTrapdoor(String username, byte[] trapdoor) {
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String searchSql = """
            SELECT d.doc_id AS storage_doc_id,
                   k.peks_ciphertext
            FROM documents d
            JOIN keyword_index k ON d.doc_id = k.doc_id
            WHERE d.owner_username = ?
            ORDER BY d.created_at DESC, d.display_doc_id ASC, k.id ASC
            """;
        Set<String> matchedStorageDocIds = new LinkedHashSet<>();

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(searchSql)) {
            // The server performs PEKS tests only with trapdoors and index ciphertext; it never decrypts plaintext keywords.
            statement.setString(1, username);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    byte[] peksCiphertext = resultSet.getBytes("peks_ciphertext");
                    if (PEKSUtil.test(peksCiphertext, trapdoor)) {
                        matchedStorageDocIds.add(resultSet.getString("storage_doc_id"));
                    }
                }
            }

            // During search, load results again by internal ID so the code can control whether large content bodies are included.
            return loadDocumentsByStorageIds(connection, username, new ArrayList<>(matchedStorageDocIds), true);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search encrypted documents", e);
        }
    }

    public List<EncryptedData> searchByCiphertext(String username, byte[] queryCiphertext) {
        String sql = """
            SELECT DISTINCT
                   d.doc_id AS storage_doc_id,
                   COALESCE(d.display_doc_id, d.doc_id) AS display_doc_id,
                   d.file_name,
                   d.mime_type,
                   d.media_type,
                   d.file_size,
                   d.created_at,
                   d.encrypted_keyword_metadata
            FROM documents d
            JOIN keyword_index k ON d.doc_id = k.doc_id
            WHERE d.owner_username = ?
              AND k.peks_ciphertext = ?
            ORDER BY d.created_at DESC, display_doc_id ASC
            """;

        List<EncryptedData> documents = new ArrayList<>();
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setBytes(2, queryCiphertext);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    documents.add(new EncryptedData(
                            resultSet.getString("display_doc_id"),
                            resultSet.getString("file_name"),
                            resultSet.getString("mime_type"),
                            resultSet.getString("media_type"),
                            resultSet.getLong("file_size"),
                            resultSet.getBytes("encrypted_keyword_metadata"),
                            null,
                            List.of()
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search encrypted documents", e);
        }
        return documents;
    }

    /**
     * Gets the current user's document summary list without returning encrypted body content.
     */
    public List<DocumentSummary> listDocuments(String username) {
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String sql = """
            SELECT COALESCE(d.display_doc_id, d.doc_id) AS display_doc_id,
                   d.file_name,
                   d.media_type,
                   d.file_size,
                   d.created_at,
                   COUNT(k.id) AS keyword_count
            FROM documents d
            LEFT JOIN keyword_index k ON d.doc_id = k.doc_id
            WHERE d.owner_username = ?
            GROUP BY d.doc_id, d.display_doc_id, d.file_name, d.media_type, d.file_size, d.created_at
            ORDER BY d.created_at DESC, display_doc_id ASC
            """;

        List<DocumentSummary> summaries = new ArrayList<>();

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    // created_at may come from historical or test data, so check for null before converting to LocalDateTime.
                    Timestamp createdAt = resultSet.getTimestamp("created_at");
                    LocalDateTime time = createdAt == null ? null : createdAt.toLocalDateTime();
                    summaries.add(new DocumentSummary(
                            resultSet.getString("display_doc_id"),
                            resultSet.getString("file_name"),
                            resultSet.getString("media_type"),
                            resultSet.getLong("file_size"),
                            resultSet.getInt("keyword_count"),
                            time
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list documents", e);
        }

        return summaries;
    }

    /**
     * Finds the full encrypted document by user-visible docId for download, index rebuild, and similar operations.
     */
    public EncryptedData findByOwnerAndDocId(String username, String docId) {
        String displayDocId = requireText(docId, "Document ID");
        String storageDocId = toStorageDocId(username, displayDocId);
        // Support new and old IDs: prefer the username-hashed internal ID, but also accept historically saved raw docIds.
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String sql = """
            SELECT doc_id AS storage_doc_id,
                   COALESCE(display_doc_id, doc_id) AS display_doc_id,
                   file_name,
                   mime_type,
                   media_type,
                   file_size,
                   encrypted_keyword_metadata,
                   encrypted_content
            FROM documents
            WHERE owner_username = ?
              AND (doc_id = ? OR display_doc_id = ? OR doc_id = ?)
            ORDER BY CASE WHEN doc_id = ? THEN 0 ELSE 1 END
            LIMIT 1
            """;

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, storageDocId);
            statement.setString(3, displayDocId);
            statement.setString(4, displayDocId);
            statement.setString(5, storageDocId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return mapDocument(connection, resultSet, resultSet.getString("storage_doc_id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load document details", e);
        }
    }

    /**
     * Deletes the specified document for the current user. Document deletion cascades keyword index deletion through foreign keys.
     */
    public boolean deleteByOwnerAndDocId(String username, String docId) {
        String displayDocId = requireText(docId, "Document ID");
        String storageDocId = toStorageDocId(username, displayDocId);
        // Deleting the document body is enough; keyword_index is automatically cleaned by ON DELETE CASCADE foreign keys.
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String sql = """
            DELETE FROM documents
            WHERE owner_username = ?
              AND (doc_id = ? OR display_doc_id = ? OR doc_id = ?)
            """;

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, storageDocId);
            statement.setString(3, displayDocId);
            statement.setString(4, displayDocId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete document", e);
        }
    }

    /**
     * Generates the internal database document ID. The username participates in the hash to isolate same-named documents across users.
     */
    public static String toStorageDocId(String username, String displayDocId) {
        String normalizedUsername = requireText(username, "Username");
        String normalizedDocId = requireText(displayDocId, "Document ID");
        // Generate a stable hash from the username and display document ID to avoid primary-key conflicts between users.
        byte[] input = (normalizedUsername + "\u0000" + normalizedDocId).getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "doc-" + HexFormat.of().formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Converts one result-set row into a document object transferable to the client.
     */
    private EncryptedData mapDocument(Connection connection, ResultSet resultSet, String storageDocId) throws SQLException {
        // Full documents include encrypted content and keyword ciphertexts, mainly for download or index rebuilding.
        return new EncryptedData(
                resultSet.getString("display_doc_id"),
                resultSet.getString("file_name"),
                resultSet.getString("mime_type"),
                resultSet.getString("media_type"),
                resultSet.getLong("file_size"),
                resultSet.getBytes("encrypted_keyword_metadata"),
                resultSet.getBytes("encrypted_content"),
                findKeywordsByStorageDocId(connection, storageDocId)
        );
    }

    /**
     * Search results return only the data actually needed by the UI.
     * For binary files such as images or audio/video, the full encrypted content is not returned to avoid pushing many large blobs through the socket in one search.
     */
    private EncryptedData mapSearchDocument(ResultSet resultSet) throws SQLException {
        String mediaType = resultSet.getString("media_type");
        boolean includeEncryptedContent = isTextMediaType(mediaType);
        // Text search results can be previewed directly, while binary search results return only metadata to save network and memory.
        return new EncryptedData(
                resultSet.getString("display_doc_id"),
                resultSet.getString("file_name"),
                resultSet.getString("mime_type"),
                mediaType,
                resultSet.getLong("file_size"),
                resultSet.getBytes("encrypted_keyword_metadata"),
                includeEncryptedContent ? resultSet.getBytes("encrypted_content") : null,
                List.of()
        );
    }

    /**
     * Reads full document details by internal document ID list, avoiding loading all encrypted bodies into memory during search.
     */
    private List<EncryptedData> loadDocumentsByStorageIds(Connection connection, String username, List<String> storageDocIds, boolean lightweightSearchResult) throws SQLException {
        if (storageDocIds.isEmpty()) {
            return List.of();
        }

        //noinspection SqlResolve,SqlNoDataSourceInspection
        String sql = """
            SELECT doc_id AS storage_doc_id,
                   COALESCE(display_doc_id, doc_id) AS display_doc_id,
                   file_name,
                   mime_type,
                   media_type,
                   file_size,
                   encrypted_keyword_metadata,
                   encrypted_content
            FROM documents
            WHERE owner_username = ?
              AND doc_id = ?
            LIMIT 1
            """;

        List<EncryptedData> documents = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String storageDocId : storageDocIds) {
                // Load IDs one by one to preserve the ordering returned by searchSql and avoid dynamic IN clause construction.
                statement.setString(1, username);
                statement.setString(2, storageDocId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        documents.add(lightweightSearchResult
                                ? mapSearchDocument(resultSet)
                                : mapDocument(connection, resultSet, storageDocId));
                    }
                }
            }
        }
        return documents;
    }

    /**
     * Reads all keyword ciphertexts for a document.
     */
    private List<byte[]> findKeywordsByStorageDocId(Connection connection, String storageDocId) throws SQLException {
        //noinspection SqlResolve,SqlNoDataSourceInspection
        String sql = "SELECT peks_ciphertext FROM keyword_index WHERE doc_id = ?";
        List<byte[]> ciphertexts = new ArrayList<>();

        // Full document download or index rebuild needs to return existing keyword ciphertexts to the client.
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, storageDocId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ciphertexts.add(resultSet.getBytes("peks_ciphertext"));
                }
            }
        }

        return ciphertexts;
    }

    /** Checks whether a search result needs to include encrypted body content. */
    private static boolean isTextMediaType(String mediaType) {
        return mediaType == null || mediaType.isBlank() || "text".equalsIgnoreCase(mediaType);
    }

    /**
     * Validates required strings and consistently trims surrounding whitespace.
     */
    private static String requireText(String value, String fieldName) {
        // Objects.requireNonNull catches null first, then trim ensures IDs and usernames have no surrounding whitespace.
        String normalized = Objects.requireNonNull(value, fieldName + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }
}
