package com.bdic.admin;

import com.bdic.model.DocumentRequest;
import com.bdic.model.EncryptedData;
import com.bdic.model.LoginRequest;
import com.bdic.model.NetworkMessage;
import com.bdic.model.ServerResponse;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

/**
 * Encapsulates protocol requests and response reads between the client and server.
 */
public class DocumentServiceClient {

    /** Object stream written to the server; all requests send NetworkMessage through it. */
    private final ObjectOutputStream out;
    /** Object stream used to read responses from the server. */
    private final ObjectInputStream in;

    /**
     * Binds object input/output streams after the TLS handshake has completed.
     */
    public DocumentServiceClient(ObjectOutputStream out, ObjectInputStream in) {
        this.out = out;
        this.in = in;
    }

    /** Sends a sign-in request and returns the server response. */
    public synchronized ServerResponse login(String username, String password) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.LOGIN, new LoginRequest(username, password));
    }

    /** Sends a registration request and returns the server response. */
    public synchronized ServerResponse register(String username, String password) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.REGISTER, new LoginRequest(username, password));
    }

    /** Notifies the server to sign out the current session. */
    public synchronized ServerResponse logout() throws Exception {
        return sendAndRead(NetworkMessage.MessageType.LOGOUT, null);
    }

    /** Uploads a document that has already been encrypted and indexed. */
    public synchronized ServerResponse upload(EncryptedData data) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.UPLOAD, data);
    }

    /** Searches the server index with a keyword trapdoor. */
    public synchronized ServerResponse search(byte[] trapdoor) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.SEARCH, trapdoor);
    }

    /** Gets the current user's document summary list. */
    public synchronized ServerResponse listDocuments() throws Exception {
        return sendAndRead(NetworkMessage.MessageType.LIST_DOCUMENTS, null);
    }

    /** Downloads the full encrypted data for the current user's specified document. */
    public synchronized ServerResponse downloadDocument(String docId) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.DOWNLOAD_DOCUMENT, new DocumentRequest(docId));
    }

    /** Deletes the current user's specified document. */
    public synchronized ServerResponse deleteDocument(String docId) throws Exception {
        return sendAndRead(NetworkMessage.MessageType.DELETE_DOCUMENT, new DocumentRequest(docId));
    }

    /**
     * Converts the server data field into an encrypted document list.
     *
     * <p>Generics are erased after object-stream deserialization; callers should use this only after confirming the response type.</p>
     */
    @SuppressWarnings("unchecked")
    public static List<EncryptedData> toEncryptedDataList(ServerResponse response) {
        return (List<EncryptedData>) response.getData();
    }

    /**
     * Sends a request through the unified protocol and synchronously waits for the server response.
     */
    private ServerResponse sendAndRead(NetworkMessage.MessageType type, Object payload) throws Exception {
        // Public methods in this class are synchronized to prevent interleaved requests on the same socket.
        out.writeObject(new NetworkMessage(type, payload));
        out.flush();
        return readServerResponse();
    }

    /**
     * Reads and validates the server response message.
     */
    private ServerResponse readServerResponse() throws Exception {
        NetworkMessage responseMessage = (NetworkMessage) in.readObject();
        Object payload = responseMessage.getPayload();
        if (!(payload instanceof ServerResponse)) {
            throw new IllegalStateException("Unexpected response payload: " + payload);
        }
        return (ServerResponse) payload;
    }
}
