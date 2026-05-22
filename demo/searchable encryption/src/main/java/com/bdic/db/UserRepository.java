package com.bdic.db;

import com.bdic.crypto.PasswordUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * User repository.
 *
 * <p>Registers new users, validates sign-in credentials, and stores passwords in "salt + hash" form in the database.</p>
 */
public class UserRepository {

    /** Database access entry point. */
    private final DatabaseManager databaseManager;

    /** Injects the database manager used by the repository for persistence operations. */
    public UserRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Registers a new user; returns false if the username already exists.
     */
    public boolean register(String username, String password) {
        // Write the username, password hash, and salt into the user table.
        String sql = "INSERT INTO users (username, password_hash, password_salt) VALUES (?, ?, ?)";
        // Generate an independent salt for each user and calculate the password hash from it.
        byte[] salt = PasswordUtil.generateSalt();
        byte[] hash = PasswordUtil.hashPassword(password, salt);

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            // PreparedStatement handles parameter binding so username or password content cannot break SQL structure.
            statement.setString(1, username);
            statement.setBytes(2, hash);
            statement.setBytes(3, salt);
            statement.executeUpdate();
            return true;
        } catch (SQLException e) {
            // A primary-key or unique-index conflict means the username already exists.
            if (e.getErrorCode() == 1062) {
                return false;
            }
            throw new RuntimeException("Failed to register user", e);
        }
    }

    /**
     * Checks whether the username and password match.
     */
    public boolean authenticate(String username, String password) {
        // Look up the saved password hash and salt by username.
        String sql = "SELECT password_hash, password_salt FROM users WHERE username = ?";

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                // Authentication fails directly when the user does not exist.
                if (!resultSet.next()) {
                    return false;
                }

                // Read the database hash and salt, then validate the entered password.
                byte[] passwordHash = resultSet.getBytes("password_hash");
                byte[] passwordSalt = resultSet.getBytes("password_salt");
                return PasswordUtil.matches(password, passwordSalt, passwordHash);
            }
        } catch (SQLException e) {
            throw new RuntimeException("User authentication failed", e);
        }
    }

    /**
     * Checks whether a username already exists.
     */
    public boolean exists(String username) {
        String sql = "SELECT 1 FROM users WHERE username = ?";

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check user existence", e);
        }
    }

    /**
     * Replaces the stored password hash and salt for an existing user.
     */
    public void updatePassword(String username, String newPassword) {
        String sql = "UPDATE users SET password_hash = ?, password_salt = ? WHERE username = ?";
        byte[] salt = PasswordUtil.generateSalt();
        byte[] hash = PasswordUtil.hashPassword(newPassword, salt);

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, hash);
            statement.setBytes(2, salt);
            statement.setString(3, username);
            if (statement.executeUpdate() == 0) {
                throw new RuntimeException("User not found");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update user password", e);
        }
    }
}
