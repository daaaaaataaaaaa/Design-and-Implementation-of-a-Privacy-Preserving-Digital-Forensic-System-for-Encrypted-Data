package com.bdic.model;

import java.io.Serializable;
import java.util.List;

/**
 * Encrypted document entity.
 *
 * <p>During client upload, this object contains document metadata, DES-encrypted content, encrypted keyword metadata,
 * and keyword ciphertexts used for server-side search.</p>
 */
public class EncryptedData implements Serializable {
    /** Java serialization version to keep client/server object-stream transfer compatible. */
    private static final long serialVersionUID = 1L;

    /** User-visible document ID; converted to an internal storage ID when saved by the server. */
    private String docId;
    /** Original file name, used for list display and restoring the file name on download. */
    private String fileName;
    /** File MIME type, used by the UI to choose a preview method. */
    private String mimeType;
    /** Simplified media category, such as text, image, video, document, or binary. */
    private String mediaType;
    /** Original file size in bytes. */
    private long fileSize;
    /** Keyword metadata encrypted with DES, containing keywords and the optional description. */
    private byte[] encryptedKeywordMetadata;
    /** Document body or binary content encrypted with DES. */
    private byte[] encryptedContent;
    /** Searchable keyword ciphertext set generated with the PEKS public key. */
    private List<byte[]> peksCiphertexts;

    /**
     * Simplified constructor for compatibility with older tests and plaintext uploads.
     *
     * <p>File metadata not explicitly passed in is filled with default text-file values.</p>
     */
    public EncryptedData(String docId, byte[] encryptedContent, List<byte[]> peksCiphertexts) {
        this(docId, docId + ".txt", "text/plain", "text", encryptedContent == null ? 0 : encryptedContent.length, null, encryptedContent, peksCiphertexts);
    }

    /**
     * Constructs a complete encrypted document entity.
     *
     * @param docId user-visible document ID.
     * @param fileName original file name.
     * @param mimeType file MIME type.
     * @param mediaType simplified media category.
     * @param fileSize original file size.
     * @param encryptedKeywordMetadata encrypted keyword metadata.
     * @param encryptedContent encrypted body or binary content.
     * @param peksCiphertexts keyword ciphertext index set.
     */
    public EncryptedData(String docId, String fileName, String mimeType, String mediaType, long fileSize, byte[] encryptedKeywordMetadata, byte[] encryptedContent, List<byte[]> peksCiphertexts) {
        this.docId = docId;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.mediaType = mediaType;
        this.fileSize = fileSize;
        this.encryptedKeywordMetadata = encryptedKeywordMetadata;
        this.encryptedContent = encryptedContent;
        this.peksCiphertexts = peksCiphertexts;
    }

    /** Returns the user-visible document ID. */
    public String getDocId() {
        return docId;
    }

    /** Sets the user-visible document ID. */
    public void setDocId(String docId) {
        this.docId = docId;
    }

    /** Returns document content encrypted with DES. */
    public byte[] getEncryptedContent() {
        return encryptedContent;
    }

    /** Sets document content encrypted with DES. */
    public void setEncryptedContent(byte[] encryptedContent) {
        this.encryptedContent = encryptedContent;
    }

    /** Returns the original file name. */
    public String getFileName() {
        return fileName;
    }

    /** Sets the original file name. */
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    /** Returns the file MIME type. */
    public String getMimeType() {
        return mimeType;
    }

    /** Sets the file MIME type. */
    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    /** Returns the simplified media category. */
    public String getMediaType() {
        return mediaType;
    }

    /** Sets the simplified media category. */
    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    /** Returns the original file size in bytes. */
    public long getFileSize() {
        return fileSize;
    }

    /** Sets the original file size in bytes. */
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    /** Returns encrypted keyword metadata. */
    public byte[] getEncryptedKeywordMetadata() {
        return encryptedKeywordMetadata;
    }

    /** Sets encrypted keyword metadata. */
    public void setEncryptedKeywordMetadata(byte[] encryptedKeywordMetadata) {
        this.encryptedKeywordMetadata = encryptedKeywordMetadata;
    }

    /** Returns the searchable keyword ciphertext set. */
    public List<byte[]> getPeksCiphertexts() {
        return peksCiphertexts;
    }

    /** Sets the searchable keyword ciphertext set, often used to replace the old set after index rebuild. */
    public void setPeksCiphertexts(List<byte[]> peksCiphertexts) {
        this.peksCiphertexts = peksCiphertexts;
    }
}
