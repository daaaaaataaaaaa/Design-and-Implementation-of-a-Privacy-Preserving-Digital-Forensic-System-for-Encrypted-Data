package com.bdic.model;

import java.io.Serializable;

/**
 * Document operation request.
 *
 * <p>Operations such as download, delete, and index rebuild only need the user-visible docId.</p>
 */
public class DocumentRequest implements Serializable {
    /** Java serialization version to keep object-stream deserialization compatible. */
    private static final long serialVersionUID = 1L;

    /** Document ID shown in the UI; the server converts it to an internal database ID. */
    private final String docId;

    /**
     * Creates a request object carrying only the document ID.
     *
     * @param docId document ID entered by the user or selected from the list.
     */
    public DocumentRequest(String docId) {
        this.docId = docId;
    }

    /**
     * Returns the user-visible document ID to operate on.
     */
    public String getDocId() {
        return docId;
    }
}
