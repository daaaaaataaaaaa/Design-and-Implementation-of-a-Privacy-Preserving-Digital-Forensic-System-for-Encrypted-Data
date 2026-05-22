package com.bdic.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Document summary information.
 *
 * <p>Used for document list display. Contains only metadata, not encrypted body content.</p>
 */
public class DocumentSummary implements Serializable {
    /** Java serialization version to keep client/server object-stream transfer compatible. */
    private static final long serialVersionUID = 1L;

    /** User-visible document ID. */
    private final String docId;
    /** Original file name or default file name generated for plaintext uploads. */
    private final String fileName;
    /** File MIME type, such as text/plain or image/png. */
    private final String mediaType;
    /** Original file size in bytes. */
    private final long fileSize;
    /** Number of encrypted keywords for this document in the keyword index table. */
    private final int keywordCount;
    /** Time when the document was first written to the database. */
    private final LocalDateTime createdAt;

    /**
     * Constructs a summary object for document list display.
     */
    public DocumentSummary(String docId, String fileName, String mediaType, long fileSize, int keywordCount, LocalDateTime createdAt) {
        this.docId = docId;
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.fileSize = fileSize;
        this.keywordCount = keywordCount;
        this.createdAt = createdAt;
    }

    /**
     * Returns the user-visible document ID.
     */
    public String getDocId() {
        return docId;
    }

    /**
     * Returns the number of indexed keywords for this document.
     */
    public int getKeywordCount() {
        return keywordCount;
    }

    /**
     * Returns the original file name.
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * Returns the media type category displayed by the UI.
     */
    public String getMediaType() {
        return mediaType;
    }

    /**
     * Returns the original file size in bytes.
     */
    public long getFileSize() {
        return fileSize;
    }

    /**
     * Returns the document creation time.
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
