package com.bdic.web;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

record AuthRequest(@NotBlank String username, @NotBlank String password) {
}

record AuthResponse(String token, String username) {
}

record DocumentDto(
        String docId,
        String fileName,
        String mimeType,
        String mediaType,
        long fileSize,
        int keywordCount,
        String createdAt,
        String plaintextPreview,
        String ciphertextBase64,
        SpreadsheetPreview spreadsheetPreview,
        int matchCount,
        List<String> matchedKeywords
) {
}

record SpreadsheetPreview(List<SpreadsheetSheetPreview> sheets, boolean truncated) {
}

record SpreadsheetSheetPreview(String name, List<List<String>> rows, int rowCount, int columnCount, boolean truncated) {
}

record DeleteResponse(boolean deleted) {
}

record HealthResponse(String status, String service) {
}

record DocumentDownload(byte[] content, String fileName, String mimeType) {
}
