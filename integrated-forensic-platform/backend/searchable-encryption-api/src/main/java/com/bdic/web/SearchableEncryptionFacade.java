package com.bdic.web;

import com.bdic.admin.DocumentOperationService;
import com.bdic.admin.DocumentOperationService.UploadContent;
import com.bdic.crypto.ClientKeyManager;
import com.bdic.crypto.DESUtil;
import com.bdic.crypto.PEKSUtil;
import com.bdic.db.DatabaseManager;
import com.bdic.db.EncryptedDataRepository;
import com.bdic.db.UserRepository;
import com.bdic.model.DocumentSummary;
import com.bdic.model.EncryptedData;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SearchableEncryptionFacade {

    private static final long MAX_AUTOMATIC_TEXT_KEYWORD_BYTES = 10L * 1024L * 1024L;
    private static final int PREVIEW_LIMIT = 8_000;

    private final EncryptedDataRepository repository;
    private final UserRepository userRepository;
    private final ClientKeyManager keyManager;
    private final DocumentOperationService operationService;
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();

    public SearchableEncryptionFacade() {
        DatabaseManager databaseManager = new DatabaseManager();
        databaseManager.initialize();
        this.repository = new EncryptedDataRepository(databaseManager);
        this.userRepository = new UserRepository(databaseManager);
        this.keyManager = new ClientKeyManager(Path.of(System.getProperty("user.home"), ".integrated-forensics", "client-keys"));
        this.operationService = new DocumentOperationService(MAX_AUTOMATIC_TEXT_KEYWORD_BYTES);
    }

    AuthResponse register(AuthRequest request) {
        boolean created = userRepository.register(request.username(), request.password());
        if (!created) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists.");
        }
        return createSession(request.username());
    }

    AuthResponse login(AuthRequest request) {
        if (!userRepository.authenticate(request.username(), request.password())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
        }
        return createSession(request.username());
    }

    List<DocumentDto> listDocuments(UserSession session) {
        return repository.listDocuments(session.username()).stream()
                .map(SearchableEncryptionFacade::fromSummary)
                .toList();
    }

    DocumentDto upload(UserSession session, String docId, String description, String text, MultipartFile file) throws Exception {
        if (!StringUtils.hasText(docId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "docId is required.");
        }

        UploadContent uploadContent = resolveUploadContent(docId, text, file);
        EncryptedData encryptedData = operationService.buildEncryptedData(
                docId,
                uploadContent,
                description == null ? "" : description,
                session.keys().desKey(),
                session.keys().peksPublicKey()
        );
        repository.save(session.username(), encryptedData);
        return fromEncryptedData(encryptedData, null, null);
    }

    List<DocumentDto> search(UserSession session, String keyword) throws Exception {
        if (!StringUtils.hasText(keyword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword is required.");
        }
        byte[] trapdoor = PEKSUtil.getTrapdoor(session.keys().peksPrivateKey(), keyword);
        return repository.searchByTrapdoor(session.username(), trapdoor).stream()
                .map(data -> fromEncryptedData(data, null, null))
                .toList();
    }

    DocumentDto find(UserSession session, String docId) {
        EncryptedData data = repository.findByOwnerAndDocId(session.username(), docId);
        if (data == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
        }

        String plaintextPreview = null;
        String ciphertextBase64 = null;
        try {
            if (operationService.isTextDocument(data)) {
                byte[] plaintext = DESUtil.decrypt(data.getEncryptedContent(), session.keys().desKey());
                plaintextPreview = truncate(new String(plaintext, StandardCharsets.UTF_8), PREVIEW_LIMIT);
            } else if (data.getEncryptedContent() != null) {
                ciphertextBase64 = Base64.getEncoder().encodeToString(data.getEncryptedContent());
            }
        } catch (Exception e) {
            ciphertextBase64 = data.getEncryptedContent() == null ? null : Base64.getEncoder().encodeToString(data.getEncryptedContent());
        }

        return fromEncryptedData(data, plaintextPreview, ciphertextBase64);
    }

    boolean delete(UserSession session, String docId) {
        return repository.deleteByOwnerAndDocId(session.username(), docId);
    }

    UserSession requireSession(String authorizationHeader) {
        String token = stripBearer(authorizationHeader);
        UserSession session = sessions.get(token);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token.");
        }
        return session;
    }

    private AuthResponse createSession(String username) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new UserSession(username, keyManager.loadOrCreate(username)));
        return new AuthResponse(token, username);
    }

    private UploadContent resolveUploadContent(String docId, String text, MultipartFile file) throws Exception {
        if (file != null && !file.isEmpty()) {
            String originalName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : docId + ".bin";
            String suffix = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf('.')) : ".bin";
            Path tempFile = Files.createTempFile("se-upload-", suffix);
            try {
                file.transferTo(tempFile);
                return operationService.resolveFileUploadContent(tempFile);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        if (!StringUtils.hasText(text)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Either text or file must be provided.");
        }
        return operationService.resolveTextUploadContent(docId, text);
    }

    private static DocumentDto fromSummary(DocumentSummary summary) {
        return new DocumentDto(
                summary.getDocId(),
                summary.getFileName(),
                null,
                summary.getMediaType(),
                summary.getFileSize(),
                summary.getKeywordCount(),
                summary.getCreatedAt() == null ? null : summary.getCreatedAt().toString(),
                null,
                null
        );
    }

    private static DocumentDto fromEncryptedData(EncryptedData data, String plaintextPreview, String ciphertextBase64) {
        int keywordCount = data.getPeksCiphertexts() == null ? 0 : data.getPeksCiphertexts().size();
        return new DocumentDto(
                data.getDocId(),
                data.getFileName(),
                data.getMimeType(),
                data.getMediaType(),
                data.getFileSize(),
                keywordCount,
                null,
                plaintextPreview,
                ciphertextBase64
        );
    }

    private static String stripBearer(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) {
            return "";
        }
        if (authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring("Bearer ".length()).trim();
        }
        return authorizationHeader.trim();
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "\n...";
    }

    record UserSession(String username, ClientKeyManager.KeyBundle keys) {
    }
}

