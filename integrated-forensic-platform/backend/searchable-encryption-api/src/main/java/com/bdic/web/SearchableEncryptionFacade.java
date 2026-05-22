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
import com.bdic.text.DocumentTextExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SearchableEncryptionFacade {

    private static final long MAX_AUTOMATIC_TEXT_KEYWORD_BYTES = 10L * 1024L * 1024L;
    private static final int PREVIEW_LIMIT = 200_000;
    private static final int SPREADSHEET_PREVIEW_MAX_SHEETS = 3;
    private static final int SPREADSHEET_PREVIEW_MAX_ROWS = 40;
    private static final int SPREADSHEET_PREVIEW_MAX_COLUMNS = 12;
    private static final String RECOVERY_CODE = getValue("se.auth.recovery-code", "SE_AUTH_RECOVERY_CODE", "12345");

    private final EncryptedDataRepository repository;
    private final UserRepository userRepository;
    private final ClientKeyManager keyManager;
    private final ClientKeyManager legacyKeyManager;
    private final Path legacyKeyDirectory;
    private final DocumentOperationService operationService;
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();

    public SearchableEncryptionFacade() {
        DatabaseManager databaseManager = new DatabaseManager();
        databaseManager.initialize();
        this.repository = new EncryptedDataRepository(databaseManager);
        this.userRepository = new UserRepository(databaseManager);
        this.keyManager = new ClientKeyManager(Path.of(System.getProperty("user.home"), ".integrated-forensics", "client-keys"));
        this.legacyKeyDirectory = Path.of(System.getProperty("user.home"), ".searchable-encryption", "client-keys");
        this.legacyKeyManager = new ClientKeyManager(legacyKeyDirectory);
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

    AuthResponse changePassword(UserSession session, ChangePasswordRequest request) {
        if (!StringUtils.hasText(request.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password is required.");
        }
        if (!userRepository.authenticate(session.username(), request.currentPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect.");
        }
        userRepository.updatePassword(session.username(), request.newPassword());
        sessions.entrySet().removeIf(entry -> entry.getValue().username().equals(session.username()));
        return createSession(session.username());
    }

    AuthResponse resetPassword(ResetPasswordRequest request) {
        if (!StringUtils.hasText(request.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password is required.");
        }
        if (!RECOVERY_CODE.equals(request.recoveryCode())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Recovery code is invalid.");
        }
        if (!userRepository.exists(request.username())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found.");
        }
        userRepository.updatePassword(request.username(), request.newPassword());
        sessions.entrySet().removeIf(entry -> entry.getValue().username().equals(request.username()));
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
        List<String> queryTokens = splitSearchKeywords(keyword);
        if (queryTokens.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword is required.");
        }

        Map<String, SearchMatch> matches = new LinkedHashMap<>();
        int rank = 0;
        for (String queryToken : queryTokens) {
            int firstRank = rank;
            byte[] queryCiphertext = PEKSUtil.encrypt(session.keys().peksPublicKey(), queryToken);
            for (EncryptedData data : repository.searchByCiphertext(session.username(), queryCiphertext)) {
                SearchMatch match = matches.computeIfAbsent(data.getDocId(), ignored -> new SearchMatch(data, firstRank, new LinkedHashSet<>()));
                match.matchedKeywords().add(queryToken);
            }
            rank++;
        }

        return matches.values().stream()
                .sorted(Comparator
                        .comparingInt((SearchMatch match) -> match.matchedKeywords().size()).reversed()
                        .thenComparingInt(SearchMatch::firstRank)
                        .thenComparing(match -> match.data().getDocId(), String.CASE_INSENSITIVE_ORDER))
                .map(match -> fromEncryptedData(match.data(), null, null, null, match.matchedKeywords().size(), new ArrayList<>(match.matchedKeywords())))
                .toList();
    }

    DocumentDto find(UserSession session, String docId) {
        EncryptedData data = repository.findByOwnerAndDocId(session.username(), docId);
        if (data == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
        }

        String plaintextPreview = null;
        String ciphertextBase64 = null;
        SpreadsheetPreview spreadsheetPreview = null;
        try {
            byte[] plaintext = decryptContent(session, data);
            if (operationService.isTextDocument(data)) {
                plaintextPreview = truncate(new String(plaintext, StandardCharsets.UTF_8), PREVIEW_LIMIT);
            } else if (isSpreadsheet(data)) {
                spreadsheetPreview = previewSpreadsheet(data, plaintext);
                if (spreadsheetPreview == null || spreadsheetPreview.sheets().isEmpty()) {
                    plaintextPreview = "No readable spreadsheet preview was extracted from this file. Use Download to inspect the original file.";
                }
            } else if ("document".equalsIgnoreCase(data.getMediaType())) {
                plaintextPreview = previewDocumentText(data, plaintext);
            } else if (data.getEncryptedContent() != null) {
                ciphertextBase64 = Base64.getEncoder().encodeToString(data.getEncryptedContent());
            }
        } catch (Exception e) {
            ciphertextBase64 = data.getEncryptedContent() == null ? null : Base64.getEncoder().encodeToString(data.getEncryptedContent());
        }

        return fromEncryptedData(data, plaintextPreview, ciphertextBase64, spreadsheetPreview);
    }

    DocumentDownload download(UserSession session, String docId) throws Exception {
        EncryptedData data = repository.findByOwnerAndDocId(session.username(), docId);
        if (data == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
        }
        if (data.getEncryptedContent() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document content not found.");
        }

        byte[] plaintext = decryptContent(session, data);
        String fileName = StringUtils.hasText(data.getFileName()) ? data.getFileName() : data.getDocId();
        String mimeType = StringUtils.hasText(data.getMimeType()) ? data.getMimeType() : "application/octet-stream";
        return new DocumentDownload(plaintext, fileName, mimeType);
    }

    DocumentDto rebuildIndex(UserSession session, String docId) throws Exception {
        EncryptedData data = repository.findByOwnerAndDocId(session.username(), docId);
        if (data == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
        }
        if (data.getEncryptedKeywordMetadata() == null || data.getEncryptedKeywordMetadata().length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keyword metadata is unavailable for this document.");
        }

        EncryptedData rebuilt = rebuildOrMigrateIndex(session, data);
        repository.save(session.username(), rebuilt);
        return fromEncryptedData(rebuilt, null, null);
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
        sessions.put(token, new UserSession(username, keyManager.loadOrCreate(username), loadLegacyKeys(username)));
        return new AuthResponse(token, username);
    }

    private ClientKeyManager.KeyBundle loadLegacyKeys(String username) {
        Path legacyKeyFile = legacyKeyDirectory.resolve(username + ".properties");
        return Files.exists(legacyKeyFile) ? legacyKeyManager.loadOrCreate(username) : null;
    }

    private UploadContent resolveUploadContent(String docId, String text, MultipartFile file) throws Exception {
        if (file != null && !file.isEmpty()) {
            String originalName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : docId + ".bin";
            String suffix = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf('.')) : ".bin";
            Path tempFile = Files.createTempFile("se-upload-", suffix);
            try {
                file.transferTo(tempFile);
                UploadContent resolved = operationService.resolveFileUploadContent(tempFile);
                return new UploadContent(
                        resolved.originalContent(),
                        originalName,
                        resolved.mimeType(),
                        resolved.mediaType(),
                        resolved.fileSize(),
                        resolved.extractedText(),
                        resolved.automaticTextKeywordsEnabled()
                );
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
                null,
                null,
                0,
                List.of()
        );
    }

    private static DocumentDto fromEncryptedData(EncryptedData data, String plaintextPreview, String ciphertextBase64) {
        return fromEncryptedData(data, plaintextPreview, ciphertextBase64, null, 0, List.of());
    }

    private static DocumentDto fromEncryptedData(
            EncryptedData data,
            String plaintextPreview,
            String ciphertextBase64,
            SpreadsheetPreview spreadsheetPreview
    ) {
        return fromEncryptedData(data, plaintextPreview, ciphertextBase64, spreadsheetPreview, 0, List.of());
    }

    private static DocumentDto fromEncryptedData(
            EncryptedData data,
            String plaintextPreview,
            String ciphertextBase64,
            SpreadsheetPreview spreadsheetPreview,
            int matchCount,
            List<String> matchedKeywords
    ) {
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
                ciphertextBase64,
                spreadsheetPreview,
                matchCount,
                matchedKeywords
        );
    }

    private static List<String> splitSearchKeywords(String keyword) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String rawToken : keyword.split("[\\s,;，；]+")) {
            String token = rawToken.trim().toLowerCase(Locale.ROOT);
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return new ArrayList<>(tokens);
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

    private static String getValue(String propertyKey, String envKey, String defaultValue) {
        String propertyValue = System.getProperty(propertyKey);
        if (StringUtils.hasText(propertyValue)) {
            return propertyValue;
        }
        String envValue = System.getenv(envKey);
        if (StringUtils.hasText(envValue)) {
            return envValue;
        }
        return defaultValue;
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "\n...";
    }

    private byte[] decryptContent(UserSession session, EncryptedData data) throws Exception {
        try {
            return DESUtil.decrypt(data.getEncryptedContent(), session.keys().desKey());
        } catch (Exception currentKeyFailure) {
            if (session.legacyKeys() != null) {
                try {
                    return DESUtil.decrypt(data.getEncryptedContent(), session.legacyKeys().desKey());
                } catch (Exception legacyKeyFailure) {
                    currentKeyFailure.addSuppressed(legacyKeyFailure);
                }
            }
            throw currentKeyFailure;
        }
    }

    private EncryptedData rebuildOrMigrateIndex(UserSession session, EncryptedData data) throws Exception {
        try {
            return operationService.rebuildIndex(
                    data,
                    session.keys().desKey(),
                    session.keys().peksPublicKey(),
                    null
            );
        } catch (Exception currentKeyFailure) {
            if (session.legacyKeys() != null) {
                try {
                    return operationService.migrateEncryption(
                            data,
                            session.legacyKeys().desKey(),
                            session.keys().desKey(),
                            session.keys().peksPublicKey(),
                            null
                    );
                } catch (Exception legacyKeyFailure) {
                    currentKeyFailure.addSuppressed(legacyKeyFailure);
                }
            }
            throw currentKeyFailure;
        }
    }

    private static String previewDocumentText(EncryptedData data, byte[] plaintext) throws Exception {
        String fileName = StringUtils.hasText(data.getFileName()) ? data.getFileName() : data.getDocId();
        String suffix = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : ".bin";
        Path tempFile = Files.createTempFile("se-preview-", suffix);
        try {
            Files.write(tempFile, plaintext);
            String extractedText = DocumentTextExtractor.extract(tempFile, data.getMimeType(), data.getMediaType());
            if (!StringUtils.hasText(extractedText)) {
                return "No readable text preview was extracted from this document. Use Download to inspect the original file.";
            }
            return truncate(extractedText, PREVIEW_LIMIT);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static boolean isSpreadsheet(EncryptedData data) {
        String fileName = data.getFileName() == null ? "" : data.getFileName().toLowerCase(Locale.ROOT);
        String mimeType = data.getMimeType() == null ? "" : data.getMimeType().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".xls")
                || fileName.endsWith(".xlsx")
                || fileName.endsWith(".csv")
                || "text/csv".equals(mimeType)
                || mimeType.contains("spreadsheet")
                || mimeType.contains("excel");
    }

    private static SpreadsheetPreview previewSpreadsheet(EncryptedData data, byte[] plaintext) throws Exception {
        String fileName = StringUtils.hasText(data.getFileName()) ? data.getFileName() : data.getDocId();
        String mimeType = data.getMimeType() == null ? "" : data.getMimeType().toLowerCase(Locale.ROOT);
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".csv") || "text/csv".equals(mimeType)) {
            return previewCsv(fileName, plaintext);
        }

        String suffix = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : ".xlsx";
        Path tempFile = Files.createTempFile("se-spreadsheet-preview-", suffix);
        try {
            Files.write(tempFile, plaintext);
            try (InputStream inputStream = Files.newInputStream(tempFile);
                 Workbook workbook = WorkbookFactory.create(inputStream)) {
                DataFormatter formatter = new DataFormatter();
                FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
                List<SpreadsheetSheetPreview> sheetPreviews = new ArrayList<>();
                int sheetLimit = Math.min(workbook.getNumberOfSheets(), SPREADSHEET_PREVIEW_MAX_SHEETS);

                for (int sheetIndex = 0; sheetIndex < sheetLimit; sheetIndex++) {
                    SpreadsheetSheetPreview sheetPreview = previewSheet(workbook.getSheetAt(sheetIndex), formatter, evaluator);
                    if (!sheetPreview.rows().isEmpty()) {
                        sheetPreviews.add(sheetPreview);
                    }
                }

                boolean truncated = workbook.getNumberOfSheets() > sheetLimit
                        || sheetPreviews.stream().anyMatch(SpreadsheetSheetPreview::truncated);
                return new SpreadsheetPreview(sheetPreviews, truncated);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static SpreadsheetSheetPreview previewSheet(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<List<String>> rows = new ArrayList<>();
        int maxColumnCount = 0;
        boolean truncated = false;

        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }

            short lastCellNum = row.getLastCellNum();
            if (lastCellNum < 0) {
                continue;
            }

            int columnLimit = Math.min(lastCellNum, SPREADSHEET_PREVIEW_MAX_COLUMNS);
            List<String> cells = new ArrayList<>();
            boolean hasValue = false;
            for (int columnIndex = 0; columnIndex < columnLimit; columnIndex++) {
                Cell cell = row.getCell(columnIndex);
                String value = cell == null ? "" : formatter.formatCellValue(cell, evaluator);
                if (StringUtils.hasText(value)) {
                    hasValue = true;
                }
                cells.add(value);
            }

            if (!hasValue) {
                continue;
            }

            maxColumnCount = Math.max(maxColumnCount, lastCellNum);
            rows.add(cells);
            if (lastCellNum > SPREADSHEET_PREVIEW_MAX_COLUMNS) {
                truncated = true;
            }
            if (rows.size() >= SPREADSHEET_PREVIEW_MAX_ROWS) {
                truncated = true;
                break;
            }
        }

        return new SpreadsheetSheetPreview(
                sheet.getSheetName(),
                rows,
                sheet.getPhysicalNumberOfRows(),
                Math.min(maxColumnCount, SPREADSHEET_PREVIEW_MAX_COLUMNS),
                truncated
        );
    }

    private static SpreadsheetPreview previewCsv(String fileName, byte[] plaintext) {
        String text = new String(plaintext, StandardCharsets.UTF_8);
        String[] lines = text.split("\\R", -1);
        List<List<String>> rows = new ArrayList<>();
        int maxColumnCount = 0;
        boolean truncated = false;

        for (String line : lines) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            List<String> parsed = parseCsvLine(line);
            if (parsed.size() > SPREADSHEET_PREVIEW_MAX_COLUMNS) {
                truncated = true;
            }
            List<String> row = parsed.subList(0, Math.min(parsed.size(), SPREADSHEET_PREVIEW_MAX_COLUMNS));
            rows.add(new ArrayList<>(row));
            maxColumnCount = Math.max(maxColumnCount, row.size());
            if (rows.size() >= SPREADSHEET_PREVIEW_MAX_ROWS) {
                truncated = true;
                break;
            }
        }

        SpreadsheetSheetPreview sheet = new SpreadsheetSheetPreview(fileName, rows, lines.length, maxColumnCount, truncated);
        return new SpreadsheetPreview(rows.isEmpty() ? List.of() : List.of(sheet), truncated);
    }

    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    record UserSession(String username, ClientKeyManager.KeyBundle keys, ClientKeyManager.KeyBundle legacyKeys) {
    }

    private record SearchMatch(EncryptedData data, int firstRank, LinkedHashSet<String> matchedKeywords) {
    }
}
