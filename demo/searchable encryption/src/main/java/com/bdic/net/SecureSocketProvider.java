package com.bdic.net;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * TLS socket factory.
 *
 * <p>The client and server share the project's built-in development certificate so local communication uses an encrypted channel. This certificate is suitable only for teaching and local demos;
 * in real deployments, replace it with a certificate issued by a trusted CA and manage the keystore password properly.</p>
 */
public final class SecureSocketProvider {

    /** Location of the built-in development certificate on the classpath. */
    private static final String KEY_STORE_RESOURCE = "/tls/searchable-encryption-dev.p12";
    /** Development certificate keystore password; real environments should use secure configuration. */
    private static final char[] KEY_STORE_PASSWORD = "changeit".toCharArray();
    /** Preferred TLS protocol versions, ordered from more secure to less secure. */
    private static final String[] PREFERRED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    /** Utility class; instantiation is not needed. */
    private SecureSocketProvider() {
    }

    /**
     * Creates the server TLS listening socket.
     */
    public static ServerSocket createServerSocket(int port) throws IOException, GeneralSecurityException {
        SSLContext context = createServerContext();
        SSLServerSocketFactory factory = context.getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(port);
        // Keep only secure protocols supported by the current JDK to avoid enabling outdated TLS versions accidentally.
        configureProtocols(serverSocket);
        serverSocket.setNeedClientAuth(false);
        return serverSocket;
    }

    /**
     * Creates a client TLS socket and actively completes the handshake.
     */
    public static Socket createClientSocket(String host, int port) throws IOException, GeneralSecurityException {
        SSLContext context = createClientContext();
        SSLSocketFactory factory = context.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket(host, port);
        configureProtocols(socket);

        // Enable hostname verification so the client confirms the certificate identity matches the connection target.
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        // Active handshake exposes certificate or protocol problems to the caller as early as possible.
        socket.startHandshake();
        return socket;
    }

    /**
     * The server context needs to load the private key to prove server identity to clients.
     */
    private static SSLContext createServerContext() throws IOException, GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();

        // The server loads the private key from the keystore to prove its identity during the TLS handshake.
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, KEY_STORE_PASSWORD);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    /**
     * The client context only needs to trust the built-in certificate for server certificate validation.
     */
    private static SSLContext createClientContext() throws IOException, GeneralSecurityException {
        KeyStore trustStore = loadKeyStore();

        // The client uses the same development certificate as the trust anchor for validating the server certificate chain.
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
        return context;
    }

    /**
     * Reads the PKCS12 keystore from the classpath.
     */
    private static KeyStore loadKeyStore() throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream inputStream = SecureSocketProvider.class.getResourceAsStream(KEY_STORE_RESOURCE)) {
            if (inputStream == null) {
                throw new IOException("TLS key store resource not found: " + KEY_STORE_RESOURCE);
            }
            keyStore.load(inputStream, KEY_STORE_PASSWORD);
        }
        return keyStore;
    }

    /**
     * Enables only TLSv1.3/TLSv1.2 supported by the current JDK.
     */
    private static void configureProtocols(SSLSocket socket) {
        socket.setEnabledProtocols(selectSupportedProtocols(socket.getSupportedProtocols()));
    }

    /**
     * Selects available TLS protocol versions for the server socket.
     */
    private static void configureProtocols(SSLServerSocket socket) {
        socket.setEnabledProtocols(selectSupportedProtocols(socket.getSupportedProtocols()));
    }

    /**
     * Filters the preference list to protocols actually supported by the current JDK/platform.
     */
    private static String[] selectSupportedProtocols(String[] supportedProtocols) {
        return Arrays.stream(PREFERRED_PROTOCOLS)
                .filter(protocol -> Arrays.asList(supportedProtocols).contains(protocol))
                .toArray(String[]::new);
    }
}
