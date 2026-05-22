package com.bdic.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
class MlServiceManager {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final String serviceUrl;
    private final String host;
    private final int port;
    private final String configuredWorkingDir;
    private final String configuredPython;
    private final long startTimeoutMs;
    private volatile Process process;

    MlServiceManager(
            @Value("${ml.service.url:http://127.0.0.1:8001}") String serviceUrl,
            @Value("${ml.service.host:0.0.0.0}") String host,
            @Value("${ml.service.port:8001}") int port,
            @Value("${ml.service.working-dir:../ml-service}") String configuredWorkingDir,
            @Value("${ml.service.python:.venv/Scripts/python.exe}") String configuredPython,
            @Value("${ml.service.start-timeout-ms:15000}") long startTimeoutMs
    ) {
        this.serviceUrl = trimTrailingSlash(serviceUrl);
        this.host = host;
        this.port = port;
        this.configuredWorkingDir = configuredWorkingDir;
        this.configuredPython = configuredPython;
        this.startTimeoutMs = startTimeoutMs;
    }

    MlServiceStatusResponse status() {
        boolean running = isRunning();
        return new MlServiceStatusResponse(
                running,
                running ? "running" : "stopped",
                serviceUrl,
                running ? "ML service is available." : "ML service is not running."
        );
    }

    synchronized MlServiceStatusResponse start() {
        if (isRunning()) {
            return new MlServiceStatusResponse(true, "running", serviceUrl, "ML service is already running.");
        }

        try {
            Path workingDir = resolveWorkingDirectory();
            Path python = resolvePython(workingDir);
            List<String> command = List.of(
                    python.toString(),
                    "-m",
                    "uvicorn",
                    "app.main:app",
                    "--host",
                    host,
                    "--port",
                    String.valueOf(port)
            );

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDir.toFile());
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(workingDir.resolve("platform-ml.out.log").toFile()));
            builder.redirectError(ProcessBuilder.Redirect.appendTo(workingDir.resolve("platform-ml.err.log").toFile()));
            process = builder.start();

            if (waitUntilRunning()) {
                return new MlServiceStatusResponse(true, "running", serviceUrl, "ML service started.");
            }
            return new MlServiceStatusResponse(false, "starting", serviceUrl, "ML service is starting; try again in a moment.");
        } catch (Exception error) {
            return new MlServiceStatusResponse(false, "error", serviceUrl, "Failed to start ML service: " + error.getMessage());
        }
    }

    private boolean isRunning() {
        Process currentProcess = process;
        if (currentProcess != null && !currentProcess.isAlive()) {
            process = null;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(serviceUrl + "/api/ml/metadata"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean waitUntilRunning() throws InterruptedException {
        Instant deadline = Instant.now().plusMillis(startTimeoutMs);
        while (Instant.now().isBefore(deadline)) {
            if (isRunning()) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private Path resolveWorkingDirectory() throws IOException {
        Path configured = Path.of(configuredWorkingDir);
        Path resolved = configured.isAbsolute()
                ? configured.normalize()
                : Path.of("").toAbsolutePath().resolve(configured).normalize();

        if (Files.isDirectory(resolved)) {
            return resolved;
        }

        Path sibling = Path.of("").toAbsolutePath().resolve("backend").resolve("ml-service").normalize();
        if (Files.isDirectory(sibling)) {
            return sibling;
        }

        throw new IOException("ML service directory was not found: " + resolved);
    }

    private Path resolvePython(Path workingDir) throws IOException {
        Path configured = Path.of(configuredPython);
        Path python = configured.isAbsolute() ? configured : workingDir.resolve(configured);
        if (Files.isRegularFile(python)) {
            return python.normalize();
        }

        Path unixPython = workingDir.resolve(".venv").resolve("bin").resolve("python");
        if (Files.isRegularFile(unixPython)) {
            return unixPython.normalize();
        }

        throw new IOException("ML service Python executable was not found: " + python);
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
