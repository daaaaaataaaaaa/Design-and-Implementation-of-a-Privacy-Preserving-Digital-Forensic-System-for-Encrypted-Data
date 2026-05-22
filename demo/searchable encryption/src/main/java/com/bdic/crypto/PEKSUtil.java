package com.bdic.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Locale;

/**
 * Searchable keyword encryption utility.
 *
 * <p>The standard PEKS idea is to generate searchable keyword ciphertext with a public key and generate trapdoors for query terms with a private key.
 * The server performs matching tests using only ciphertext and trapdoors. This project does not add a bilinear-pairing library, so it uses the JDK's
 * built-in RSA trapdoor permutation to express a teaching-level asymmetric PEKS interface; it is not production-grade standard PEKS.</p>
 */
public class PEKSUtil {

    /** Asymmetric algorithm used for search keys. */
    private static final String KEY_ALGORITHM = "RSA";

    /** RSA search key length. */
    private static final int KEY_SIZE_BITS = 2048;

    /** Hash algorithm used when mapping keywords to integer representatives. */
    private static final String HASH_ALGORITHM = "SHA-256";

    /** Binary payload format marker used to avoid parsing old data as the new format. */
    private static final byte[] MAGIC = new byte[]{'P', 'E', 'K', 'S'};

    /** Keyword ciphertext payload type. */
    private static final byte CIPHERTEXT_TYPE = 1;

    /** Query trapdoor payload type. */
    private static final byte TRAPDOOR_TYPE = 2;

    /** Hash domain-separation label to avoid confusion with other SHA-256 uses in the project. */
    private static final byte[] KEYWORD_HASH_DOMAIN = "PEKS-RSA-KEYWORD-v1".getBytes(StandardCharsets.US_ASCII);

    /**
     * Generates a PEKS search key pair. The public key creates keyword ciphertext during upload, and the private key creates trapdoors during search.
     */
    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        keyPairGenerator.initialize(KEY_SIZE_BITS);
        return keyPairGenerator.generateKeyPair();
    }

    /**
     * Restores a PEKS public key from locally stored X.509 bytes.
     */
    public static PublicKey getPublicKeyFromBytes(byte[] keyBytes) throws GeneralSecurityException {
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        return keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    /**
     * Restores a PEKS private key from locally stored PKCS#8 bytes.
     */
    public static PrivateKey getPrivateKeyFromBytes(byte[] keyBytes) throws GeneralSecurityException {
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    /**
     * Generates searchable ciphertext for a keyword with the search public key and stores it in the server index table with the document.
     */
    public static byte[] encrypt(PublicKey publicSearchKey, String keyword) throws Exception {
        RSAPublicKey rsaPublicKey = requireRsaPublicKey(publicSearchKey);
        BigInteger keywordRepresentative = keywordRepresentative(rsaPublicKey.getModulus(), keyword);
        BigInteger encryptedRepresentative = keywordRepresentative.modPow(
                rsaPublicKey.getPublicExponent(),
                rsaPublicKey.getModulus()
        );

        return encodePayload(
                CIPHERTEXT_TYPE,
                rsaPublicKey.getModulus(),
                rsaPublicKey.getPublicExponent(),
                encryptedRepresentative
        );
    }

    /**
     * Generates a trapdoor for a query term with the search private key; the server matches it against keyword ciphertext.
     */
    public static byte[] getTrapdoor(PrivateKey privateSearchKey, String query) throws Exception {
        RSAPrivateKey rsaPrivateKey = requireRsaPrivateKey(privateSearchKey);
        BigInteger keywordRepresentative = keywordRepresentative(rsaPrivateKey.getModulus(), query);
        BigInteger trapdoorValue = keywordRepresentative.modPow(
                rsaPrivateKey.getPrivateExponent(),
                rsaPrivateKey.getModulus()
        );

        return encodePayload(TRAPDOOR_TYPE, rsaPrivateKey.getModulus(), trapdoorValue);
    }

    /**
     * Compatibility entry point for older code; delegates to the formal trapdoor generation method.
     */
    public static byte[] getInternalTrapdoor(PrivateKey key, String keyword) throws Exception {
        return getTrapdoor(key, keyword);
    }

    /**
     * Lets the server test whether a PEKS ciphertext is matched by the current trapdoor.
     */
    public static boolean test(byte[] peksCiphertext, byte[] trapdoor) {
        try {
            RsaPeksCiphertext ciphertext = decodeCiphertext(peksCiphertext);
            RsaPeksTrapdoor queryTrapdoor = decodeTrapdoor(trapdoor);
            if (!ciphertext.modulus().equals(queryTrapdoor.modulus())) {
                return false;
            }

            BigInteger recoveredKeywordRepresentative = queryTrapdoor.value().modPow(
                    ciphertext.publicExponent(),
                    ciphertext.modulus()
            );
            BigInteger expectedCiphertext = recoveredKeywordRepresentative.modPow(
                    ciphertext.publicExponent(),
                    ciphertext.modulus()
            );
            int encodedLength = unsignedLength(ciphertext.modulus());
            return MessageDigest.isEqual(
                    toFixedLength(ciphertext.encryptedRepresentative(), encodedLength),
                    toFixedLength(expectedCiphertext, encodedLength)
            );
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Normalizes a keyword and maps it to a stable representative in the RSA modulus space.
     */
    private static BigInteger keywordRepresentative(BigInteger modulus, String keyword) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
        digest.update(KEYWORD_HASH_DOMAIN);
        digest.update((byte) 0);
        digest.update(normalizeKeyword(keyword).getBytes(StandardCharsets.UTF_8));

        BigInteger representative = new BigInteger(1, digest.digest());
        if (representative.signum() == 0) {
            return BigInteger.ONE;
        }
        if (representative.compareTo(modulus) >= 0) {
            return representative.mod(modulus.subtract(BigInteger.ONE)).add(BigInteger.ONE);
        }
        return representative;
    }

    /**
     * Normalizes keyword case and surrounding whitespace so upload and search use the same matching form.
     */
    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Encodes a PEKS ciphertext or trapdoor. Each BigInteger is written as unsigned big-endian bytes.
     */
    private static byte[] encodePayload(byte payloadType, BigInteger... values) {
        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(byteStream);
            output.write(MAGIC);
            output.writeByte(payloadType);
            output.writeByte(values.length);
            for (BigInteger value : values) {
                byte[] encodedValue = toUnsignedBytes(value);
                output.writeInt(encodedValue.length);
                output.write(encodedValue);
            }
            output.flush();
            return byteStream.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode PEKS payload", e);
        }
    }

    /**
     * Decodes and validates PEKS keyword ciphertext.
     */
    private static RsaPeksCiphertext decodeCiphertext(byte[] payload) {
        BigInteger[] values = decodePayload(payload, CIPHERTEXT_TYPE, 3);
        return new RsaPeksCiphertext(values[0], values[1], values[2]);
    }

    /**
     * Decodes and validates a PEKS query trapdoor.
     */
    private static RsaPeksTrapdoor decodeTrapdoor(byte[] payload) {
        BigInteger[] values = decodePayload(payload, TRAPDOOR_TYPE, 2);
        return new RsaPeksTrapdoor(values[0], values[1]);
    }

    /**
     * Decodes a generic PEKS payload.
     */
    private static BigInteger[] decodePayload(byte[] payload, byte expectedType, int expectedValueCount) {
        if (payload == null) {
            throw new IllegalArgumentException("PEKS payload is required");
        }

        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IllegalArgumentException("Unsupported PEKS payload");
            }

            byte payloadType = input.readByte();
            int valueCount = input.readUnsignedByte();
            if (payloadType != expectedType || valueCount != expectedValueCount) {
                throw new IllegalArgumentException("Unexpected PEKS payload type");
            }

            BigInteger[] values = new BigInteger[valueCount];
            for (int i = 0; i < valueCount; i++) {
                int length = input.readInt();
                if (length <= 0 || length > 8192) {
                    throw new IllegalArgumentException("Invalid PEKS integer length");
                }

                byte[] valueBytes = input.readNBytes(length);
                if (valueBytes.length != length) {
                    throw new IllegalArgumentException("Truncated PEKS payload");
                }
                values[i] = new BigInteger(1, valueBytes);
            }

            if (input.available() != 0) {
                throw new IllegalArgumentException("Trailing bytes in PEKS payload");
            }
            return values;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid PEKS payload", e);
        }
    }

    /**
     * Requires callers to pass an RSA public key.
     */
    private static RSAPublicKey requireRsaPublicKey(PublicKey publicKey) {
        if (publicKey instanceof RSAPublicKey rsaPublicKey) {
            return rsaPublicKey;
        }
        throw new IllegalArgumentException("PEKS public key must be an RSA public key");
    }

    /**
     * Requires callers to pass an RSA private key.
     */
    private static RSAPrivateKey requireRsaPrivateKey(PrivateKey privateKey) {
        if (privateKey instanceof RSAPrivateKey rsaPrivateKey) {
            return rsaPrivateKey;
        }
        throw new IllegalArgumentException("PEKS private key must be an RSA private key");
    }

    /**
     * BigInteger.toByteArray may include a sign bit; convert it consistently to an unsigned representation here.
     */
    private static byte[] toUnsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    /**
     * Left-pads to a fixed length to support constant-time comparison.
     */
    private static byte[] toFixedLength(BigInteger value, int length) {
        byte[] unsigned = toUnsignedBytes(value);
        if (unsigned.length == length) {
            return unsigned;
        }

        byte[] fixed = new byte[length];
        int copyLength = Math.min(unsigned.length, length);
        System.arraycopy(unsigned, unsigned.length - copyLength, fixed, length - copyLength, copyLength);
        return fixed;
    }

    /**
     * Returns the unsigned encoded length.
     */
    private static int unsignedLength(BigInteger value) {
        return toUnsignedBytes(value).length;
    }

    /**
     * RSA PEKS keyword ciphertext structure.
     */
    private record RsaPeksCiphertext(BigInteger modulus, BigInteger publicExponent, BigInteger encryptedRepresentative) {
    }

    /**
     * RSA PEKS query trapdoor structure.
     */
    private record RsaPeksTrapdoor(BigInteger modulus, BigInteger value) {
    }
}
