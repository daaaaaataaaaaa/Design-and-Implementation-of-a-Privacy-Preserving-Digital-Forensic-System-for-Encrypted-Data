package com.bdic.web;

import jakarta.validation.constraints.NotBlank;

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
        String ciphertextBase64
) {
}

record DeleteResponse(boolean deleted) {
}

record HealthResponse(String status, String service) {
}

