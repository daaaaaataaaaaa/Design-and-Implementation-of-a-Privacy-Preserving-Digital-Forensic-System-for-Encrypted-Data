package com.bdic.admin;

import com.bdic.crypto.DESUtil;
import com.bdic.crypto.PEKSUtil;
import com.bdic.model.EncryptedData;
import com.bdic.text.DocumentTextExtractor;
import com.bdic.text.KeywordExtractor;

import javax.crypto.SecretKey;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles core logic for document upload, index rebuilding, and keyword processing.
 */
public class DocumentOperationService {
    /** Prefix used to mark user description lines in keyword metadata; the value itself is Base64-encoded. */
    public static final String DESCRIPTION_METADATA_PREFIX = "__description_b64__:";

    /** Maximum file size allowed for automatic text keyword extraction. */
    private final long maxAutomaticTextKeywordBytes;

    /**
     * Creates the document operation service.
     *
     * @param maxAutomaticTextKeywordBytes automatic body keyword extraction is attempted only when a file does not exceed this size.
     */
    public DocumentOperationService(long maxAutomaticTextKeywordBytes) {
        this.maxAutomaticTextKeywordBytes = maxAutomaticTextKeywordBytes;
    }

    /**
     * Wraps plaintext from the input box into the unified upload content object.
     */
    public UploadContent resolveTextUploadContent(String docId, String content) {
        // Plaintext uploads have no real file name, so derive a txt file name from the docId.
        byte[] originalContent = content.getBytes(StandardCharsets.UTF_8);
        return new UploadContent(originalContent, docId + ".txt", "text/plain", "text", originalContent.length, content, true);
    }

    /**
     * Reads a local file, detects its type, and extracts searchable text when appropriate.
     */
    public UploadContent resolveFileUploadContent(Path path) throws IOException {
        long fileSize = Files.size(path);
        byte[] originalContent = Files.readAllBytes(path);
        String fileName = path.getFileName().toString();
        String mimeType = detectMimeType(path);
        String mediaType = inferMediaType(fileName, mimeType);
        boolean automaticTextKeywordsEnabled = shouldUseAutomaticTextKeywords(mediaType, fileSize);
        // Try extracting body text from readable documents such as text, PDF, Word, and spreadsheets; skip images, audio/video, and large files.
        String extractedText = automaticTextKeywordsEnabled
                ? DocumentTextExtractor.extract(path, mimeType, mediaType)
                : "";
        return new UploadContent(originalContent, fileName, mimeType, mediaType, fileSize, extractedText, automaticTextKeywordsEnabled);
    }

    /**
     * Creates an encrypted document entity from upload content for sending to the server.
     */
    public EncryptedData buildEncryptedData(String docId, UploadContent uploadContent, String descriptionInput, SecretKey desKey, PublicKey peksPublicKey) throws Exception {
        List<String> keywords = resolveKeywords(descriptionInput, uploadContent);
        if (keywords.isEmpty()) {
            throw new IllegalArgumentException("No searchable keywords were found for " + uploadContent.fileName());
        }

        // Encrypt body content with DES and convert keywords to searchable ciphertext with the PEKS public key.
        byte[] encryptedContent = DESUtil.encrypt(uploadContent.originalContent(), desKey);
        List<byte[]> peksCiphertexts = new ArrayList<>();
        for (String keyword : buildSearchableTokens(keywords)) {
            peksCiphertexts.add(PEKSUtil.encrypt(peksPublicKey, keyword));
        }

        return new EncryptedData(
                docId,
                uploadContent.fileName(),
                uploadContent.mimeType(),
                uploadContent.mediaType(),
                uploadContent.fileSize(),
                encryptKeywordMetadata(keywords, descriptionInput, desKey),
                encryptedContent,
                peksCiphertexts
        );
    }

    /**
     * Regenerates the encrypted keyword index for a document.
     *
     * <p>Original keywords are restored from encrypted keyword metadata first; when old documents lack metadata, the user is asked to enter them manually.</p>
     */
    public EncryptedData rebuildIndex(EncryptedData data, SecretKey desKey, PublicKey peksPublicKey, Component parent) throws Exception {
        String[] originalKeywords = resolveOriginalKeywords(data, desKey, parent);
        if (originalKeywords == null || originalKeywords.length == 0) {
            throw new IllegalStateException("No keywords available for reindexing.");
        }

        List<byte[]> rebuiltCiphertexts = new ArrayList<>();
        // Rebuild uses the same token expansion rules as upload so prefix-search behavior remains consistent.
        for (String token : buildSearchableTokens(originalKeywords)) {
            rebuiltCiphertexts.add(PEKSUtil.encrypt(peksPublicKey, token));
        }
        data.setPeksCiphertexts(rebuiltCiphertexts);
        data.setEncryptedKeywordMetadata(encryptKeywordMetadata(originalKeywords, desKey));
        return data;
    }

    public EncryptedData migrateEncryption(
            EncryptedData data,
            SecretKey sourceDesKey,
            SecretKey targetDesKey,
            PublicKey targetPeksPublicKey,
            Component parent
    ) throws Exception {
        String[] originalKeywords = resolveOriginalKeywords(data, sourceDesKey, parent);
        if (originalKeywords == null || originalKeywords.length == 0) {
            throw new IllegalStateException("No keywords available for reindexing.");
        }

        List<byte[]> rebuiltCiphertexts = new ArrayList<>();
        for (String token : buildSearchableTokens(originalKeywords)) {
            rebuiltCiphertexts.add(PEKSUtil.encrypt(targetPeksPublicKey, token));
        }

        if (data.getEncryptedContent() != null) {
            byte[] plaintext = DESUtil.decrypt(data.getEncryptedContent(), sourceDesKey);
            data.setEncryptedContent(DESUtil.encrypt(plaintext, targetDesKey));
        }
        data.setPeksCiphertexts(rebuiltCiphertexts);
        data.setEncryptedKeywordMetadata(encryptKeywordMetadata(originalKeywords, targetDesKey));
        return data;
    }

    /**
     * Collects all regular files under a folder and sorts them by path for batch upload.
     */
    public List<Path> collectFiles(Path folder) throws IOException {
        try (var stream = Files.walk(folder)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
        }
    }

    /**
     * Returns the suggested file name for download.
     */
    public String defaultFileName(EncryptedData data) {
        return data.getFileName() == null || data.getFileName().isBlank() ? data.getDocId() : data.getFileName();
    }

    /**
     * Generates a path in the target directory without overwriting existing files.
     */
    public Path resolveUniqueChildPath(Path directory, String fileName) {
        Path candidate = directory.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }

        // Split "a.txt" into "a" and ".txt"; on conflicts generate names such as "a (1).txt".
        String baseName = fileName;
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }

        int counter = 1;
        while (true) {
            Path nextCandidate = directory.resolve(baseName + " (" + counter + ")" + extension);
            if (!Files.exists(nextCandidate)) {
                return nextCandidate;
            }
            counter++;
        }
    }

    /**
     * Extracts the most suitable exception message for display to the user.
     */
    public static String describeException(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        String message = rootCause.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        message = throwable.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return rootCause.getClass().getSimpleName();
    }

    /**
     * Checks whether an encrypted document can be previewed as text.
     */
    public boolean isTextDocument(EncryptedData data) {
        if (data.getMediaType() == null || data.getMediaType().isBlank()) {
            return true;
        }
        return isTextDocument(data.getMediaType());
    }

    /**
     * Checks whether a media category is text.
     */
    public boolean isTextDocument(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return true;
        }
        return "text".equalsIgnoreCase(mediaType);
    }

    /**
     * Combines the user description, file name, and extractable body text into a deduplicated keyword set.
     */
    private List<String> resolveKeywords(String descriptionInput, UploadContent uploadContent) {
        Set<String> keywords = new LinkedHashSet<>();
        boolean jsonUpload = isJsonUpload(uploadContent);
        // User description has the highest priority, followed by file name and then automatically extracted body text.
        keywords.addAll(KeywordExtractor.extractWords(descriptionInput));
        keywords.addAll(KeywordExtractor.extractFileNameKeywords(uploadContent.fileName()));
        if (jsonUpload) {
            // JSON evidence files read only explicit keyword fields to avoid noisy tokens from whole-document JSON tokenization.
            String jsonText = new String(uploadContent.originalContent(), StandardCharsets.UTF_8);
            keywords.addAll(KeywordExtractor.extractJsonKeywordFields(jsonText));
        }
        if (!jsonUpload && uploadContent.automaticTextKeywordsEnabled() && uploadContent.extractedText() != null && !uploadContent.extractedText().isBlank()) {
            keywords.addAll(KeywordExtractor.extractWords(uploadContent.extractedText()));
        } else if (!jsonUpload && uploadContent.automaticTextKeywordsEnabled() && isTextDocument(uploadContent.mediaType())) {
            // If the text extractor returns no content, plaintext files can fall back to direct UTF-8 reading.
            String text = new String(uploadContent.originalContent(), StandardCharsets.UTF_8);
            keywords.addAll(KeywordExtractor.extractWords(text));
        }
        return new ArrayList<>(keywords);
    }

    /**
     * Checks whether upload content should be handled as JSON.
     */
    private boolean isJsonUpload(UploadContent uploadContent) {
        String mimeType = uploadContent.mimeType() == null ? "" : uploadContent.mimeType().toLowerCase(Locale.ROOT);
        String fileName = uploadContent.fileName() == null ? "" : uploadContent.fileName().toLowerCase(Locale.ROOT);
        return "application/json".equals(mimeType)
                || mimeType.endsWith("+json")
                || fileName.endsWith(".json");
    }

    /**
     * Checks whether automatic text keyword extraction is allowed for a file.
     */
    private boolean shouldUseAutomaticTextKeywords(String mediaType, long fileSize) {
        return fileSize <= maxAutomaticTextKeywordBytes && supportsAutomaticTextKeywords(mediaType);
    }

    /**
     * Checks whether a media category supports body keyword extraction.
     */
    private boolean supportsAutomaticTextKeywords(String mediaType) {
        return isTextDocument(mediaType)
                || "document".equalsIgnoreCase(mediaType)
                || "spreadsheet".equalsIgnoreCase(mediaType);
    }

    /**
     * Uses system facilities to detect the MIME type, falling back to the file name on failure.
     */
    private String detectMimeType(Path path) {
        try {
            String detectedMimeType = Files.probeContentType(path);
            if (detectedMimeType != null && !detectedMimeType.isBlank()) {
                return detectedMimeType;
            }
            return guessMimeTypeFromFileName(path.getFileName() == null ? null : path.getFileName().toString());
        } catch (IOException e) {
            return guessMimeTypeFromFileName(path.getFileName() == null ? null : path.getFileName().toString());
        }
    }

    /**
     * Maps MIME types and extensions into the coarse media categories used by the UI.
     */
    private String inferMediaType(String fileName, String mimeType) {
        String normalizedMimeType = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        String normalizedName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);

        // Prefer the MIME main type, then fall back to the extension when MIME is missing or inaccurate.
        if (normalizedMimeType.startsWith("image/")) {
            return "image";
        }
        if (normalizedMimeType.startsWith("video/")) {
            return "video";
        }
        if (normalizedMimeType.startsWith("audio/")) {
            return "audio";
        }
        if (normalizedMimeType.startsWith("text/")
                || "application/json".equals(normalizedMimeType)
                || normalizedMimeType.endsWith("+json")
                || normalizedName.endsWith(".json")) {
            return "text";
        }
        if (normalizedName.endsWith(".xls") || normalizedName.endsWith(".xlsx") || normalizedName.endsWith(".csv")
                || normalizedMimeType.contains("spreadsheet") || normalizedMimeType.contains("excel")) {
            return "spreadsheet";
        }
        if (normalizedName.endsWith(".pdf") || normalizedName.endsWith(".doc") || normalizedName.endsWith(".docx")
                || normalizedName.endsWith(".ppt")
                || normalizedName.endsWith(".pptx")) {
            return "document";
        }
        return "binary";
    }

    /**
     * Provides fallback types from common extensions when system MIME detection fails.
     */
    private String guessMimeTypeFromFileName(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        String normalizedName = fileName.toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith(".json")) {
            return "application/json";
        }
        return "application/octet-stream";
    }

    /**
     * Accepts array-form keyword input and converts it to a list internally.
     */
    private List<String> buildSearchableTokens(String[] keywords) {
        return buildSearchableTokens(Arrays.asList(keywords));
    }

    /**
     * Generates the tokens that actually enter the PEKS index from a keyword set.
     *
     * <p>Besides complete keywords, prefixes starting at length 2 are added to support user-entered prefix search.</p>
     */
    private List<String> buildSearchableTokens(List<String> keywords) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String rawKeyword : keywords) {
            if (rawKeyword == null) {
                continue;
            }

            String keyword = rawKeyword.trim().toLowerCase(Locale.ROOT);
            if (keyword.isEmpty()) {
                continue;
            }

            tokens.add(keyword);
            if (keyword.length() <= 2) {
                continue;
            }
            // For example, searchable adds se, sea, sear, and so on so prefix trapdoors can match.
            for (int i = 2; i < keyword.length(); i++) {
                tokens.add(keyword.substring(0, i));
            }
        }
        return new ArrayList<>(tokens);
    }

    /**
     * Compatibility entry point for encrypting keyword metadata without a user description.
     */
    private byte[] encryptKeywordMetadata(String[] keywords, SecretKey desKey) throws Exception {
        return encryptKeywordMetadata(Arrays.asList(keywords), null, desKey);
    }

    /**
     * Writes keywords and the optional description as text, then stores them encrypted with DES.
     */
    private byte[] encryptKeywordMetadata(List<String> keywords, String descriptionInput, SecretKey desKey) throws Exception {
        List<String> normalizedKeywords = new ArrayList<>();
        for (String rawKeyword : keywords) {
            if (rawKeyword == null) {
                continue;
            }
            String keyword = rawKeyword.trim().toLowerCase(Locale.ROOT);
            if (!keyword.isEmpty()) {
                normalizedKeywords.add(keyword);
            }
        }

        if (normalizedKeywords.isEmpty()) {
            return null;
        }

        List<String> metadataLines = new ArrayList<>();
        String normalizedDescription = descriptionInput == null ? "" : descriptionInput.trim();
        if (!normalizedDescription.isEmpty()) {
            // Descriptions may contain newlines or special characters, so Base64-encode them before placing them in line-based metadata.
            String encodedDescription = Base64.getEncoder().encodeToString(normalizedDescription.getBytes(StandardCharsets.UTF_8));
            metadataLines.add(DESCRIPTION_METADATA_PREFIX + encodedDescription);
        }
        metadataLines.addAll(normalizedKeywords);

        // The metadata is encrypted as a whole, so the server cannot read user descriptions or plaintext keywords.
        String joinedMetadata = String.join("\n", metadataLines);
        return DESUtil.encrypt(joinedMetadata.getBytes(StandardCharsets.UTF_8), desKey);
    }

    /**
     * Restores the original keywords required for index rebuilding.
     */
    private String[] resolveOriginalKeywords(EncryptedData data, SecretKey desKey, Component parent) throws Exception {
        byte[] encryptedKeywordMetadata = data.getEncryptedKeywordMetadata();
        if (encryptedKeywordMetadata != null && encryptedKeywordMetadata.length > 0) {
            // New documents store encrypted keyword metadata; rebuild decrypts it directly and filters out description lines.
            String restoredKeywords = new String(DESUtil.decrypt(encryptedKeywordMetadata, desKey), StandardCharsets.UTF_8);
            return Arrays.stream(restoredKeywords.split("\\R"))
                    .map(String::trim)
                    .filter(keyword -> !keyword.isEmpty() && !keyword.startsWith(DESCRIPTION_METADATA_PREFIX))
                    .toArray(String[]::new);
        }

        // Old documents do not store keyword metadata, so the user must manually enter the original keywords.
        String keywordsInput = requestLegacyKeywords(parent);
        if (keywordsInput == null || keywordsInput.isBlank()) {
            return null;
        }
        return Arrays.stream(keywordsInput.split(","))
                .map(String::trim)
                .filter(keyword -> !keyword.isEmpty())
                .toArray(String[]::new);
    }

    /**
     * Ensures the input dialog is always shown on the Swing event thread when old documents need manual keywords.
     */
    private String requestLegacyKeywords(Component parent) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return showLegacyKeywordInput(parent);
        }

        final String[] input = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> input[0] = showLegacyKeywordInput(parent));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        return input[0];
    }

    /**
     * Shows the keyword input dialog for old documents.
     */
    private String showLegacyKeywordInput(Component parent) {
        return JOptionPane.showInputDialog(
                parent,
                "This legacy document has no stored keyword metadata.\nPlease enter the original keywords separated by commas:",
                "Rebuild Index",
                JOptionPane.PLAIN_MESSAGE
        );
    }

    /**
     * Unified content description before upload.
     *
     * @param originalContent original plaintext bytes.
     * @param fileName original file name or auto-generated text file name.
     * @param mimeType file MIME type.
     * @param mediaType simplified media category.
     * @param fileSize original file size.
     * @param extractedText automatically extracted body text, possibly empty.
     * @param automaticTextKeywordsEnabled whether body-based automatic keyword generation is allowed.
     */
    public record UploadContent(
            byte[] originalContent,
            String fileName,
            String mimeType,
            String mediaType,
            long fileSize,
            String extractedText,
            boolean automaticTextKeywordsEnabled
    ) {
    }
}
