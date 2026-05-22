package com.bdic.admin;

import com.bdic.db.DatabaseManager;
import com.bdic.db.EncryptedDataRepository;
import com.bdic.db.UserRepository;
import com.bdic.model.DocumentRequest;
import com.bdic.model.DocumentSummary;
import com.bdic.model.EncryptedData;
import com.bdic.model.LoginRequest;
import com.bdic.model.NetworkMessage;
import com.bdic.model.ServerResponse;
import com.bdic.model.SessionInfo;
import com.bdic.net.SecureSocketProvider;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server entry point.
 *
 * <p>Starts the TLS listener, maintains signed-in sessions, and handles client requests such as registration, sign-in, upload, search,
 * download, and deletion. AdminClientApp can start this server inside the same JVM through startEmbedded().</p>
 */
public class Server {

    /** Server listening port; the client connects to the same port. */
    private static final int PORT = 12345;
    /** Session timeout duration, overrideable with the se.session.timeout.minutes system property. */
    private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(
            Long.getLong("se.session.timeout.minutes", 30)
    );
    /** In-memory session table keyed by sessionId. */
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    /** Whether the embedded server has started, preventing clients from launching duplicate listener threads. */
    private static final AtomicBoolean EMBEDDED_SERVER_STARTED = new AtomicBoolean(false);

    /** Encrypted document repository responsible for persisting documents and keyword indexes. */
    private final EncryptedDataRepository repository;
    /** User repository responsible for registration and authentication. */
    private final UserRepository userRepository;

    /**
     * Initializes the database schema and creates repositories required by the server.
     */
    public Server() {
        DatabaseManager databaseManager = new DatabaseManager();
        databaseManager.initialize();
        this.repository = new EncryptedDataRepository(databaseManager);
        this.userRepository = new UserRepository(databaseManager);
    }

    /**
     * Starts the blocking server listen loop. This method is suitable for standalone server runs or background threads.
     */
    public void start() {
        try (ServerSocket serverSocket = SecureSocketProvider.createServerSocket(PORT)) {
            System.out.println("TLS server is listening on port " + PORT);

            while (true) {
                // Clean expired sessions before waiting for each new connection; this is cheap and needs no scheduler thread.
                cleanupExpiredSessions();
                Socket clientSocket = serverSocket.accept();
                System.out.println("New TLS client connected: " + clientSocket.getInetAddress());
                new ClientHandler(clientSocket).start();
            }
        } catch (Exception e) {
            System.err.println("Server exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Starts the embedded server. If it has already started, return immediately to avoid binding the port twice.
     */
    public static void startEmbedded() {
        if (!EMBEDDED_SERVER_STARTED.compareAndSet(false, true)) {
            return;
        }

        Thread serverThread = new Thread(() -> {
            try {
                new Server().start();
            } catch (Exception e) {
                EMBEDDED_SERVER_STARTED.set(false);
                throw e;
            }
        }, "searchable-encryption-embedded-server");
        // The embedded server exits with the client process.
        serverThread.setDaemon(true);
        serverThread.start();
    }

    /**
     * Cleans expired sessions so the session table does not grow without bound during long runs.
     */
    private static void cleanupExpiredSessions() {
        Instant now = Instant.now();
        SESSIONS.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
    }

    /**
     * Handler thread for a single client connection.
     */
    private class ClientHandler extends Thread {
        /** Underlying socket for the current connection. */
        private final Socket socket;
        /** sessionId bound to the current connection, available only after sign-in. */
        private String currentSessionId;
        /** Username bound to the current connection, available only after sign-in. */
        private String currentUsername;

        /**
         * Creates an independent handler thread for one client connection.
         */
        private ClientHandler(Socket socket) {
            this.socket = socket;
        }

        /**
         * Continuously reads client messages and dispatches them to business logic by message type.
         */
        @Override
        public void run() {
            try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

                while (true) {
                    // Both client and server wrap request/response objects in NetworkMessage.
                    NetworkMessage message = (NetworkMessage) in.readObject();
                    if (message == null) {
                        break;
                    }

                    try {
                        // Authentication requests can be handled directly; document requests first require the current connection to be signed in.
                        switch (message.getType()) {
                            case REGISTER:
                                handleRegister(message, out);
                                break;

                            case LOGIN:
                                handleLogin(message, out);
                                break;

                            case LOGOUT:
                                closeSession();
                                writeResponse(out, true, "Logged out.", null);
                                break;

                            case UPLOAD:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                EncryptedData data = (EncryptedData) message.getPayload();
                                // The server still stores only encrypted content and encrypted keywords, never client plaintext.
                                repository.save(currentUsername, data);
                                System.out.println("Stored document " + data.getDocId() + " for " + currentUsername);
                                writeResponse(out, true, "Upload succeeded.", null);
                                break;

                            case SEARCH:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                byte[] trapdoor = (byte[]) message.getPayload();
                                // Search passes only the trapdoor to the repository for PEKS tests; the server never sees plaintext keywords.
                                List<EncryptedData> matchedData = repository.searchByTrapdoor(currentUsername, trapdoor);
                                writeResponse(out, true, "Search completed.", matchedData);
                                break;

                            case LIST_DOCUMENTS:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                List<DocumentSummary> documentSummaries = repository.listDocuments(currentUsername);
                                writeResponse(out, true, "Document list loaded.", documentSummaries);
                                break;

                            case DOWNLOAD_DOCUMENT:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                DocumentRequest downloadRequest = (DocumentRequest) message.getPayload();
                                // Include the current username during lookup to prevent users from downloading other users' documents by docId.
                                EncryptedData document = repository.findByOwnerAndDocId(currentUsername, downloadRequest.getDocId());
                                if (document == null) {
                                    writeResponse(out, false, "Document not found.", null);
                                } else {
                                    writeResponse(out, true, "Download ready.", document);
                                }
                                break;

                            case DELETE_DOCUMENT:
                                if (!ensureAuthenticated(out)) {
                                    break;
                                }
                                DocumentRequest deleteRequest = (DocumentRequest) message.getPayload();
                                boolean deleted = repository.deleteByOwnerAndDocId(currentUsername, deleteRequest.getDocId());
                                writeResponse(out, deleted, deleted ? "Delete succeeded." : "Document not found.", null);
                                break;

                            default:
                                writeResponse(out, false, "Unsupported message type: " + message.getType(), null);
                        }
                    } catch (Exception e) {
                        // Convert internal exceptions into client-readable messages while keeping the full stack trace on the server.
                        String clientMessage = buildClientSafeErrorMessage(message.getType(), e);
                        System.err.println("Client request failed: " + clientMessage);
                        e.printStackTrace();
                        writeResponse(out, false, clientMessage, null);
                    }
                }
            } catch (java.io.EOFException e) {
                System.out.println("Client disconnected.");
            } catch (Exception e) {
                System.err.println("Client handler exception: " + e.getMessage());
                e.printStackTrace();
            } finally {
                closeSession();
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        /**
         * Creates a signed-in session immediately after successful registration.
         */
        private void handleRegister(NetworkMessage message, ObjectOutputStream out) throws IOException {
            LoginRequest loginRequest = (LoginRequest) message.getPayload();
            // The username is the primary key; the repository converts duplicate usernames into false.
            boolean created = userRepository.register(loginRequest.getUsername(), loginRequest.getPassword());
            if (!created) {
                writeResponse(out, false, "Username already exists.", null);
                return;
            }

            openSession(loginRequest.getUsername(), out, "Registration succeeded.");
        }

        /**
         * Validates username and password, then creates a signed-in session on success.
         */
        private void handleLogin(NetworkMessage message, ObjectOutputStream out) throws IOException {
            LoginRequest loginRequest = (LoginRequest) message.getPayload();
            // The server validates only password hashes and never writes plaintext passwords to the database.
            boolean authenticated = userRepository.authenticate(loginRequest.getUsername(), loginRequest.getPassword());
            if (!authenticated) {
                writeResponse(out, false, "Invalid username or password.", null);
                return;
            }

            openSession(loginRequest.getUsername(), out, "Login succeeded.");
        }

        /**
         * Creates a new server-side session and returns the session information to the client.
         */
        private void openSession(String username, ObjectOutputStream out, String message) throws IOException {
            // Close the old session first when the same connection signs in again, avoiding multiple identities on one connection.
            closeSession();
            Session session = Session.create(username);
            SESSIONS.put(session.sessionId, session);
            currentSessionId = session.sessionId;
            currentUsername = username;
            writeResponse(out, true, message, session.toInfo());
        }

        /**
         * Checks whether the current connection is signed in and the session is still valid; refreshes the expiry time when valid.
         */
        private boolean ensureAuthenticated(ObjectOutputStream out) throws IOException {
            if (currentSessionId == null || currentUsername == null || currentUsername.isBlank()) {
                writeResponse(out, false, "Please log in first.", null);
                return false;
            }

            Session session = SESSIONS.get(currentSessionId);
            if (session == null || !currentUsername.equals(session.username)) {
                // Missing sessions or username mismatches are treated as invalid sessions.
                currentSessionId = null;
                currentUsername = null;
                writeResponse(out, false, "Session is no longer valid. Please log in again.", null);
                return false;
            }

            if (session.isExpired()) {
                closeSession();
                writeResponse(out, false, "Session expired. Please log in again.", null);
                return false;
            }

            // Valid requests slide the expiry time forward so active users are not signed out mid-session.
            session.refresh();
            return true;
        }

        /**
         * Actively removes the session bound to the current connection.
         */
        private void closeSession() {
            if (currentSessionId != null) {
                SESSIONS.remove(currentSessionId);
            }
            currentSessionId = null;
            currentUsername = null;
        }

        /**
         * Writes a response back to the client through the unified protocol.
         */
        private void writeResponse(ObjectOutputStream out, boolean success, String message, Object data) throws IOException {
            // Responses also use NetworkMessage so the protocol layer handles only one top-level object type.
            out.writeObject(new NetworkMessage(
                    NetworkMessage.MessageType.RESPONSE,
                    new ServerResponse(success, message, data)
            ));
            out.flush();
        }
    }

    /**
     * Converts server exceptions into concise error messages displayable by the client.
     */
    private static String buildClientSafeErrorMessage(NetworkMessage.MessageType type, Exception exception) {
        Throwable rootCause = findRootCause(exception);
        String detail = rootCause.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = rootCause.getClass().getSimpleName();
        }
        if ("PacketTooBigException".equals(rootCause.getClass().getSimpleName())) {
            return inferActionLabel(type) + " failed: file is too large for the current MySQL max_allowed_packet limit. " + detail;
        }
        return inferActionLabel(type) + " failed: " + detail;
    }

    /**
     * Converts a message type into a user-facing operation name.
     */
    private static String inferActionLabel(NetworkMessage.MessageType type) {
        if (type == null) {
            return "Request";
        }
        return switch (type) {
            case REGISTER -> "Registration";
            case LOGIN -> "Login";
            case LOGOUT -> "Logout";
            case UPLOAD -> "Upload";
            case SEARCH -> "Search";
            case LIST_DOCUMENTS -> "Document list";
            case DOWNLOAD_DOCUMENT -> "Download";
            case DELETE_DOCUMENT -> "Delete";
            case RESPONSE -> "Request";
        };
    }

    /**
     * Walks the exception chain to find the root cause, making the actual failure point easier to report.
     */
    private static Throwable findRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * In-memory server-side session object.
     */
    private static class Session {
        /** Randomly generated session identifier. */
        private final String sessionId;
        /** User that owns the session. */
        private final String username;
        /** Session expiration time; volatile lets different threads see refreshed values. */
        private volatile Instant expiresAt;

        /**
         * Constructs the internal session object.
         */
        private Session(String sessionId, String username, Instant expiresAt) {
            this.sessionId = sessionId;
            this.username = username;
            this.expiresAt = expiresAt;
        }

        /**
         * Creates a new session with a random sessionId.
         */
        private static Session create(String username) {
            return new Session(UUID.randomUUID().toString(), username, Instant.now().plus(SESSION_TIMEOUT));
        }

        /** Checks whether the current session has expired. */
        private boolean isExpired() {
            return expiresAt.isBefore(Instant.now());
        }

        /** Extends the expiration time by one timeout period. */
        private void refresh() {
            expiresAt = Instant.now().plus(SESSION_TIMEOUT);
        }

        /**
         * Converts the internal session into a DTO serializable to the client.
         */
        private SessionInfo toInfo() {
            LocalDateTime localExpiresAt = LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault());
            return new SessionInfo(sessionId, username, localExpiresAt);
        }
    }

}
