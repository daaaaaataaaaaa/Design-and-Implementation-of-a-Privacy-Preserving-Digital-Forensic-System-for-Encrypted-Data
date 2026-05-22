package com.bdic.model;

import java.io.Serializable;

/**
 * Sign-in/registration request payload.
 *
 * <p>This object is transferred through the TLS channel. After receiving it, the server stores only the password hash, not the plaintext password.</p>
 */
public class LoginRequest implements Serializable {
    /** Java serialization version to keep object-stream deserialization compatible. */
    private static final long serialVersionUID = 1L;

    /** Username entered during sign-in or registration. */
    private final String username;
    /** Plaintext password entered during sign-in or registration, transmitted only temporarily inside the TLS channel. */
    private final String password;

    /**
     * Constructs an authentication request.
     *
     * @param username username.
     * @param password plaintext password; the server immediately converts it into a salted hash after receiving it.
     */
    public LoginRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /** Returns the username. */
    public String getUsername() {
        return username;
    }

    /** Returns the plaintext password, only for server-side authentication flow use. */
    public String getPassword() {
        return password;
    }
}
