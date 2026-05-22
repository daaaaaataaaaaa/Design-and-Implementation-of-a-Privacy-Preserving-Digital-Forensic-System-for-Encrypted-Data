package com.bdic.model;

import java.io.Serializable;

/**
 * Unified message object transferred between client and server.
 *
 * <p>type indicates the operation type, and payload carries the corresponding request or response data. Objects are transferred through TLS socket object streams.</p>
 */
public class NetworkMessage implements Serializable {
    /** Java serialization version to keep client and server object-stream structures consistent. */
    private static final long serialVersionUID = 1L;

    /**
     * Protocol message type.
     *
     * <p>The client uses types other than RESPONSE for business requests, and the server always uses RESPONSE
     * to wrap {@link ServerResponse} return values.</p>
     */
    public enum MessageType {
        // User authentication operations.
        REGISTER,
        LOGIN,
        LOGOUT,

        // Document upload and encrypted keyword search.
        UPLOAD,
        SEARCH,

        // Document management operations.
        LIST_DOCUMENTS,
        DOWNLOAD_DOCUMENT,
        DELETE_DOCUMENT,

        // Unified server response.
        RESPONSE
    }

    /** Business type of this message. */
    private MessageType type;
    /** Payload object corresponding to the type, such as LoginRequest, EncryptedData, or ServerResponse. */
    private Object payload;

    /**
     * Constructs one protocol message.
     *
     * @param type operation type.
     * @param payload data required by the corresponding operation; may be null.
     */
    public NetworkMessage(MessageType type, Object payload) {
        this.type = type;
        this.payload = payload;
    }

    /** Returns the message type. */
    public MessageType getType() {
        return type;
    }

    /** Sets the message type, usually only for deserialization or protocol extension. */
    public void setType(MessageType type) {
        this.type = type;
    }

    /** Returns the message payload. */
    public Object getPayload() {
        return payload;
    }

    /** Sets the message payload. */
    public void setPayload(Object payload) {
        this.payload = payload;
    }
}
