package com.bdic.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * Unified server response object.
 *
 * <p>success indicates whether the operation succeeded, message is used for UI prompts, and data carries the specific business result.</p>
 */
public class ServerResponse implements Serializable {
    /** Java serialization version to keep response objects compatible in object streams. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** Whether the operation succeeded. */
    private final boolean success;
    /** Information shown to the user or caller. */
    private final String message;
    /** Business return data, such as session information, document lists, or downloaded encrypted documents. */
    private final Object data;

    /**
     * Constructs a unified server response.
     */
    public ServerResponse(boolean success, String message, Object data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    /** Returns whether the operation succeeded. */
    public boolean isSuccess() {
        return success;
    }

    /** Returns the server message. */
    public String getMessage() {
        return message;
    }

    /** Returns the business data payload. */
    public Object getData() {
        return data;
    }
}
