package com.bdic.admin;

import com.bdic.crypto.ClientKeyManager;
import com.bdic.model.ServerResponse;
import com.bdic.model.SessionInfo;
import com.bdic.net.SecureSocketProvider;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Desktop client entry point for the administration UI.
 *
 * <p>Responsibilities: window startup, tab assembly, global state coordination, and connection lifecycle management.</p>
 */
public class AdminClientApp extends JFrame {

    /** The client connects to the local server by default. */
    private static final String HOST = "127.0.0.1";
    /** TLS listening port shared by the client and server. */
    private static final int PORT = 12345;
    /** Maximum number of connection retries after the embedded server starts. */
    private static final int EMBEDDED_SERVER_RETRIES = 20;
    /** Delay between checks for embedded server readiness. */
    private static final long EMBEDDED_SERVER_RETRY_DELAY_MS = 300L;
    /** Maximum file size for automatic text keyword extraction to avoid UI stalls on large uploads. */
    private static final long MAX_AUTOMATIC_TEXT_KEYWORD_BYTES = 10L * 1024 * 1024;
    /** Base main-window width used by the UI scaling manager. */
    private static final int WINDOW_BASE_WIDTH = 760;
    /** Base main-window height used by the UI scaling manager. */
    private static final int WINDOW_BASE_HEIGHT = 560;

    /** Loads or creates local keys for the current user. */
    private final ClientKeyManager keyManager = new ClientKeyManager();

    /** Currently signed-in username. */
    private String currentUsername;
    /** DES key and PEKS search key pair for the current user. */
    private ClientKeyManager.KeyBundle keyBundle;

    /** TLS socket connected to the server. */
    private Socket socket;
    /** Object output stream sent to the server. */
    private ObjectOutputStream out;
    /** Object input stream receiving server responses. */
    private ObjectInputStream in;

    /** Client service that wraps protocol reads and writes. */
    private DocumentServiceClient serviceClient;
    /** Handles local document operations such as encryption, decryption, and index building. */
    private DocumentOperationService operationService;
    /** Centrally manages busy states for background tasks on all pages. */
    private UiBusyStateManager busyStateManager;

    /** Upload page controller. */
    private UploadPanelController uploadController;
    /** Search page controller. */
    private SearchPanelController searchController;
    /** Document management page controller. */
    private DocumentsPanelController documentsController;

    /** Header logout button, disabled while background tasks are running. */
    private JButton logoutButton;

    /**
     * Constructs the client main window.
     *
     * <p>Startup order: connect to the server, complete sign-in or registration, load local keys, and create the three workflow pages.</p>
     */
    public AdminClientApp() {
        try {
            connectToServer();
            serviceClient = new DocumentServiceClient(out, in);
            if (!showAuthenticationDialog()) {
                closeConnection();
                dispose();
                return;
            }
            operationService = new DocumentOperationService(MAX_AUTOMATIC_TEXT_KEYWORD_BYTES);
            createUI();
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Failed to initialize client: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Connects to the TLS server; if no local server exists, starts the embedded server and retries.
     */
    private void connectToServer() throws Exception {
        try {
            openConnection();
            return;
        } catch (Exception firstFailure) {
            if (!isConnectionRefused(firstFailure)) {
                throw firstFailure;
            }
            System.out.println("No TLS server found on " + HOST + ":" + PORT + ". Starting embedded server...");
        }

        Server.startEmbedded();
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= EMBEDDED_SERVER_RETRIES; attempt++) {
            try {
                // The embedded server needs a little time to initialize the database and start listening.
                Thread.sleep(EMBEDDED_SERVER_RETRY_DELAY_MS);
                openConnection();
                return;
            } catch (Exception retryFailure) {
                lastFailure = retryFailure;
            }
        }

        throw new IOException("Embedded server did not become ready on " + HOST + ":" + PORT, lastFailure);
    }

    /**
     * Opens a TLS socket and creates object input/output streams on it.
     */
    private void openConnection() throws Exception {
        socket = SecureSocketProvider.createClientSocket(HOST, PORT);
        out = new ObjectOutputStream(socket.getOutputStream());
        in = new ObjectInputStream(socket.getInputStream());
        System.out.println("Connected to TLS server at " + HOST + ":" + PORT);
    }

    /**
     * Checks whether the exception chain contains connection refused, distinguishing "server not started" from other connection errors.
     */
    private boolean isConnectionRefused(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Shows the sign-in/registration dialog and loads the current user's keys after success.
     */
    private boolean showAuthenticationDialog() throws Exception {
        JTextField usernameField = new JTextField();
        JPasswordField passwordField = new JPasswordField();
        Object[] message = {
                "Username:", usernameField,
                "Password:", passwordField
        };

        while (true) {
            Object[] options = {"Login", "Register", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                    this,
                    message,
                    "Authentication",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    options,
                    options[0]
            );

            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
                return false;
            }

            String username = usernameField.getText().trim();
            String password = new String(passwordField.getPassword());
            if (username.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Username and password are required.", "Warning", JOptionPane.WARNING_MESSAGE);
                continue;
            }

            // Call register or login based on the user's choice; the server returns session information on success.
            ServerResponse response = (choice == 1)
                    ? serviceClient.register(username, password)
                    : serviceClient.login(username, password);
            showResponse(response, "Authentication");
            if (!response.isSuccess()) {
                continue;
            }

            currentUsername = username;
            if (response.getData() instanceof SessionInfo sessionInfo) {
                currentUsername = sessionInfo.getUsername();
            }

            // User keys are stored only on the client; the server never receives the DES key or PEKS private key.
            keyBundle = keyManager.loadOrCreate(currentUsername);
            return true;
        }
    }

    /**
     * Creates the main-window UI and assembles the Upload, Search, and Documents pages into tabs.
     */
    private void createUI() {
        setTitle("Searchable Encryption System - Client");
        setSize(WINDOW_BASE_WIDTH, WINDOW_BASE_HEIGHT);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // When closing the window, sign out from the server before releasing the socket.
                logoutAndExit(false);
            }
        });

        JLabel userInfoLabel = new JLabel("Current User: " + currentUsername);
        userInfoLabel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        logoutButton = new JButton("Logout");
        logoutButton.addActionListener(e -> logoutAndExit(true));
        UiComponentFactory.styleDangerButton(logoutButton);

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.add(userInfoLabel, BorderLayout.CENTER);
        headerPanel.add(logoutButton, BorderLayout.EAST);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        add(headerPanel, BorderLayout.NORTH);

        // The three pages share one server connection, one local key bundle, and one document operation service.
        uploadController = new UploadPanelController(this, serviceClient, operationService, keyBundle, this::refreshDocuments);
        searchController = new SearchPanelController(this, serviceClient, operationService, keyBundle);
        documentsController = new DocumentsPanelController(this, serviceClient, operationService, keyBundle);

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Upload", uploadController.createPanel());
        tabbedPane.addTab("Search", searchController.createPanel());
        tabbedPane.addTab("Documents", documentsController.createPanel());
        add(tabbedPane, BorderLayout.CENTER);

        busyStateManager = new UiBusyStateManager(
                this,
                uploadController.getStatusLabel(),
                uploadController.getProgressBar(),
                searchController.getStatusLabel(),
                searchController.getProgressBar(),
                documentsController.getStatusLabel(),
                documentsController.getProgressBar()
        );

        uploadController.setBusyStateManager(busyStateManager);
        searchController.setBusyStateManager(busyStateManager);
        documentsController.setBusyStateManager(busyStateManager);

        // Register every control that can trigger network or file operations so the busy-state manager can disable them consistently.
        List<JComponent> busySensitive = new ArrayList<>();
        busySensitive.addAll(uploadController.getBusySensitiveComponents());
        busySensitive.addAll(searchController.getBusySensitiveComponents());
        busySensitive.addAll(documentsController.getBusySensitiveComponents());
        busySensitive.add(logoutButton);
        busyStateManager.registerBusySensitiveComponents(busySensitive);

        UiScaleManager.install(this, WINDOW_BASE_WIDTH, WINDOW_BASE_HEIGHT);
        refreshDocuments();
    }

    /**
     * Refreshes the document list. Upload success also calls this to keep the Documents page synchronized.
     */
    private void refreshDocuments() {
        if (documentsController != null) {
            documentsController.refreshDocuments();
        }
    }

    /**
     * Displays server responses in a unified dialog.
     */
    private void showResponse(ServerResponse response, String title) {
        JOptionPane.showMessageDialog(
                this,
                response.getMessage(),
                title,
                response.isSuccess() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE
        );
    }

    /**
     * Signs out the current session and exits the program.
     */
    private void logoutAndExit(boolean showDialog) {
        boolean forceExit = false;
        if (busyStateManager != null && busyStateManager.isBusy()) {
            Object[] options = {"Continue Waiting", "Force Exit"};
            int choice = JOptionPane.showOptionDialog(
                    this,
                    "An operation is still in progress.\nForce exiting will interrupt the current task. Continue?",
                    "Operation In Progress",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    options,
                    options[0]
            );
            if (choice != 1) {
                return;
            }
            forceExit = true;
        }
        try {
            if (!forceExit && serviceClient != null) {
                // Even if server-side logout fails, finally closes the local connection and exits.
                ServerResponse response = serviceClient.logout();
                if (showDialog) {
                    showResponse(response, "Logout");
                }
            }
        } catch (Exception e) {
            if (showDialog) {
                JOptionPane.showMessageDialog(this, "Logout failed: " + e.getMessage(), "Warning", JOptionPane.WARNING_MESSAGE);
            }
        } finally {
            closeConnection();
            dispose();
            System.exit(0);
        }
    }

    /**
     * Closes the local socket and ignores errors during shutdown.
     */
    private void closeConnection() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Swing program entry point: installs the FlatLaf theme and creates the main window on the event-dispatch thread.
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            FlatLightLaf.setup();
            AdminClientApp clientApp = new AdminClientApp();
            if (clientApp.currentUsername != null) {
                clientApp.setVisible(true);
            }
        });
    }
}
