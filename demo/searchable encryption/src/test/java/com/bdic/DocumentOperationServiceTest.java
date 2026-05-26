package com.bdic;

import com.bdic.admin.DocumentOperationService;
import com.bdic.crypto.DESUtil;
import com.bdic.crypto.PEKSUtil;
import com.bdic.model.EncryptedData;
import junit.framework.TestCase;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;

/**
 * Tests document upload keyword preparation.
 */
public class DocumentOperationServiceTest extends TestCase {

    /**
     * Verifies that JSON evidence saved through the text upload path keeps explicit searchable keyword values.
     */
    public void testTextUploadIndexesJsonSearchableKeywordValues() throws Exception {
        DocumentOperationService service = new DocumentOperationService(10L * 1024L * 1024L);
        SecretKey desKey = DESUtil.generateKey();
        KeyPair peksKeys = PEKSUtil.generateKeyPair();
        String evidenceJson = "{\n"
                + "  \"Searchable_Keywords\": [\"PROTOCOL:udp\", \"SERVICE:dns\", \"STATE:INT\"],\n"
                + "  \"Forensic_Metrics\": {\"Protocol\": \"udp\"}\n"
                + "}";

        EncryptedData encryptedData = service.buildEncryptedData(
                "DET-test",
                service.resolveTextUploadContent("DET-test", evidenceJson),
                "",
                desKey,
                peksKeys.getPublic()
        );

        String metadata = new String(DESUtil.decrypt(encryptedData.getEncryptedKeywordMetadata(), desKey), StandardCharsets.UTF_8);
        assertTrue(metadata.contains("protocol:udp"));
        assertTrue(metadata.contains("service:dns"));
        assertTrue(metadata.contains("state:int"));
    }

    /**
     * Verifies that rebuilding older text evidence can recover explicit JSON keyword values from encrypted content.
     */
    public void testRebuildIndexEnrichesOlderTextEvidenceFromJsonContent() throws Exception {
        DocumentOperationService service = new DocumentOperationService(10L * 1024L * 1024L);
        SecretKey desKey = DESUtil.generateKey();
        KeyPair peksKeys = PEKSUtil.generateKeyPair();
        String evidenceJson = "{\n"
                + "  \"Searchable_Keywords\": [\"PROTOCOL:udp\", \"SERVICE:dns\", \"STATE:INT\"],\n"
                + "  \"Forensic_Metrics\": {\"Protocol\": \"udp\"}\n"
                + "}";
        String oldMetadata = "protocol\nudp\nservice\ndns\nstate\nint";
        EncryptedData olderData = new EncryptedData(
                "DET-old",
                "DET-old.txt",
                "text/plain",
                "text",
                evidenceJson.getBytes(StandardCharsets.UTF_8).length,
                DESUtil.encrypt(oldMetadata.getBytes(StandardCharsets.UTF_8), desKey),
                DESUtil.encrypt(evidenceJson.getBytes(StandardCharsets.UTF_8), desKey),
                new ArrayList<>()
        );

        EncryptedData rebuilt = service.rebuildIndex(olderData, desKey, peksKeys.getPublic(), null);

        String metadata = new String(DESUtil.decrypt(rebuilt.getEncryptedKeywordMetadata(), desKey), StandardCharsets.UTF_8);
        assertTrue(metadata.contains("protocol:udp"));
        assertTrue(metadata.contains("service:dns"));
        assertTrue(metadata.contains("state:int"));
    }
}
