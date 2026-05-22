package com.bdic.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Database connection and schema initialization entry point.
 *
 * <p>Configuration is read in this order: JVM system properties, environment variables, then defaults. When the server starts, it automatically creates the database,
 * user table, document table, and keyword index table, while filling fields missing from older versions.</p>
 */
public class DatabaseManager {

    /** Database host address. */
    private final String host;
    /** Database service port. */
    private final int port;
    /** Business database name. */
    private final String databaseName;
    /** Database login username. */
    private final String username;
    /** Database login password. */
    private final String password;

    /** Builds database connection configuration from system properties, environment variables, or defaults. */
    public DatabaseManager() {
        this(
                getValue("se.db.host", "SE_DB_HOST", "localhost"),
                Integer.parseInt(getValue("se.db.port", "SE_DB_PORT", "3306")),
                getValue("se.db.name", "SE_DB_NAME", "searchable_encryption"),
                getValue("se.db.user", "SE_DB_USER", "root"),
                getValue("se.db.password", "SE_DB_PASSWORD", "123456ysy")
        );
    }

    /** Constructs the manager with explicitly provided database parameters. */
    public DatabaseManager(String host, int port, String databaseName, String username, String password) {
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.username = username;
        this.password = password;
    }

    /** Gets a JDBC connection to the business database. */
    public Connection getConnection() throws SQLException {
        // Each call creates a new connection; repository methods release it with try-with-resources.
        return DriverManager.getConnection(buildDatabaseJdbcUrl(), username, password);
    }

    /**
     * Creates the business database and required schema. Repeated execution is safe and suitable for every server startup.
     */
    public void initialize() {
        // Step one connects to the MySQL service itself and ensures the business database exists.
        try (Connection serverConnection = DriverManager.getConnection(buildServerJdbcUrl(), username, password);
             Statement serverStatement = serverConnection.createStatement()) {
            serverStatement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + databaseName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create database", e);
        }

        // Step two connects to the business database and creates or completes all business tables.
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            // The user table stores only password hashes and salts, never plaintext passwords.
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS users (
                    username VARCHAR(100) PRIMARY KEY,
                    password_hash VARBINARY(255) NOT NULL,
                    password_salt VARBINARY(255) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // The document table stores encrypted content and display metadata; owner_username provides multi-user isolation.
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS documents (
                    doc_id VARCHAR(255) PRIMARY KEY,
                    display_doc_id VARCHAR(255) NOT NULL,
                    owner_username VARCHAR(100) NOT NULL,
                    file_name VARCHAR(255) NOT NULL,
                    mime_type VARCHAR(255) NOT NULL,
                    media_type VARCHAR(50) NOT NULL,
                    file_size BIGINT NOT NULL DEFAULT 0,
                    encrypted_keyword_metadata LONGBLOB NULL,
                    encrypted_content LONGBLOB NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // The keyword index table stores searchable ciphertext for each keyword and is automatically cleaned by foreign keys on document deletion.
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS keyword_index (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    doc_id VARCHAR(255) NOT NULL,
                    peks_ciphertext VARBINARY(2048) NOT NULL,
                    CONSTRAINT fk_keyword_document
                        FOREIGN KEY (doc_id) REFERENCES documents(doc_id)
                        ON DELETE CASCADE
                )
                """);

            // The following column additions support historical schemas and avoid manual migrations after upgrades.
            ensureColumnExists(statement, "documents", "display_doc_id", "VARCHAR(255) NULL");
            ensureColumnExists(statement, "documents", "owner_username", "VARCHAR(100) NOT NULL DEFAULT 'system'");
            ensureColumnExists(statement, "documents", "file_name", "VARCHAR(255) NOT NULL DEFAULT 'unknown.bin'");
            ensureColumnExists(statement, "documents", "mime_type", "VARCHAR(255) NOT NULL DEFAULT 'application/octet-stream'");
            ensureColumnExists(statement, "documents", "media_type", "VARCHAR(50) NOT NULL DEFAULT 'binary'");
            ensureColumnExists(statement, "documents", "file_size", "BIGINT NOT NULL DEFAULT 0");
            ensureColumnExists(statement, "documents", "encrypted_keyword_metadata", "LONGBLOB NULL");
            ensureColumnExists(statement, "users", "created_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            ensureColumnExists(statement, "documents", "created_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            statement.executeUpdate("UPDATE documents SET display_doc_id = doc_id WHERE display_doc_id IS NULL OR display_doc_id = ''");
            modifyColumn(statement, "keyword_index", "peks_ciphertext", "VARBINARY(2048) NOT NULL");

            // Create indexes for common query conditions to improve per-user document listing and search.
            if (!indexExists(connection, "documents", "idx_documents_owner")) {
                statement.executeUpdate("CREATE INDEX idx_documents_owner ON documents(owner_username)");
            }

            if (!indexExists(connection, "documents", "idx_documents_owner_display")) {
                statement.executeUpdate("CREATE INDEX idx_documents_owner_display ON documents(owner_username, display_doc_id)");
            }

            if (!indexExists(connection, "keyword_index", "idx_keyword_doc")) {
                statement.executeUpdate("CREATE INDEX idx_keyword_doc ON keyword_index(doc_id)");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    /**
     * Attempts to add a column to a historical table; ignores MySQL duplicate-column errors when the column already exists.
     */
    private void ensureColumnExists(Statement statement, String tableName, String columnName, String columnDefinition) throws SQLException {
        try {
            statement.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
        } catch (SQLException e) {
            // 1060 means the column already exists, so the current schema is already sufficient.
            if (e.getErrorCode() != 1060) {
                throw e;
            }
        }
    }

    /** Adjusts an existing column definition; if the target column is missing, later compatibility logic handles it. */
    private void modifyColumn(Statement statement, String tableName, String columnName, String columnDefinition) throws SQLException {
        try {
            statement.executeUpdate("ALTER TABLE " + tableName + " MODIFY COLUMN " + columnName + " " + columnDefinition);
        } catch (SQLException e) {
            // 1054 means the column does not exist; ignore it here for historical-version compatibility.
            if (e.getErrorCode() != 1054) {
                throw e;
            }
        }
    }

    /** Checks whether the target index already exists on the specified table. */
    private boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getIndexInfo(connection.getCatalog(), null, tableName, false, false)) {
            while (resultSet.next()) {
                String existingIndexName = resultSet.getString("INDEX_NAME");
                if (indexName.equalsIgnoreCase(existingIndexName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Builds the JDBC URL that connects to the database server itself for database creation. */
    private String buildServerJdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
    }

    /** Builds the JDBC URL for the business database. */
    private String buildDatabaseJdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + databaseName
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
    }

    /**
     * Reads JVM parameters first, then environment variables, and finally defaults.
     */
    private static String getValue(String propertyKey, String envKey, String defaultValue) {
        String propertyValue = System.getProperty(propertyKey);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        return defaultValue;
    }
}
