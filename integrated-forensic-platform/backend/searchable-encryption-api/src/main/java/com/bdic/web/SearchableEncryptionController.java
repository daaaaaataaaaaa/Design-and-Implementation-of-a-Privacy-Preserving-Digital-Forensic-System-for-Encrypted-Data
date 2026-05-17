package com.bdic.web;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/se")
public class SearchableEncryptionController {

    private final SearchableEncryptionFacade facade;

    public SearchableEncryptionController(SearchableEncryptionFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/health")
    HealthResponse health() {
        return new HealthResponse("ok", "searchable-encryption-api");
    }

    @PostMapping("/auth/register")
    AuthResponse register(@Valid @RequestBody AuthRequest request) {
        return facade.register(request);
    }

    @PostMapping("/auth/login")
    AuthResponse login(@Valid @RequestBody AuthRequest request) {
        return facade.login(request);
    }

    @GetMapping("/documents")
    List<DocumentDto> listDocuments(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return facade.listDocuments(facade.requireSession(authorization));
    }

    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    DocumentDto upload(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String docId,
            @RequestParam(required = false, defaultValue = "") String description,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) MultipartFile file
    ) throws Exception {
        return facade.upload(facade.requireSession(authorization), docId, description, text, file);
    }

    @GetMapping("/documents/search")
    List<DocumentDto> search(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String keyword
    ) throws Exception {
        return facade.search(facade.requireSession(authorization), keyword);
    }

    @GetMapping("/documents/{docId}")
    DocumentDto find(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String docId
    ) {
        return facade.find(facade.requireSession(authorization), docId);
    }

    @GetMapping("/documents/{docId}/download")
    ResponseEntity<byte[]> download(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String docId
    ) throws Exception {
        DocumentDownload download = facade.download(facade.requireSession(authorization), docId);
        MediaType contentType = MediaType.APPLICATION_OCTET_STREAM;
        try {
            contentType = MediaType.parseMediaType(download.mimeType());
        } catch (Exception ignored) {
            // Fall back to octet-stream for unknown or malformed MIME types.
        }

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(download.content());
    }

    @PostMapping("/documents/{docId}/rebuild-index")
    DocumentDto rebuildIndex(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String docId
    ) throws Exception {
        return facade.rebuildIndex(facade.requireSession(authorization), docId);
    }

    @DeleteMapping("/documents/{docId}")
    DeleteResponse delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String docId
    ) {
        return new DeleteResponse(facade.delete(facade.requireSession(authorization), docId));
    }
}
