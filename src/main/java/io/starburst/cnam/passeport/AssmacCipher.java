package io.starburst.cnam.passeport;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.GeneralSecurityException;

/**
 * AES-256-GCM decryption for the assmac_act column, encrypted at ingestion time
 * outside this plugin (Python, cryptography.hazmat.primitives.ciphers.aead.AESGCM).
 *
 * Contract (must match the Python side exactly):
 * - Algorithm: AES/GCM/NoPadding, 256-bit key (32 raw bytes)
 * - IV/nonce: 12 random bytes, unique per value
 * - GCM tag: 16 bytes, appended to the ciphertext by the cipher itself (not handled separately)
 * - Wire format: base64( IV(12 bytes) || ciphertext_with_tag_appended )
 * - No AAD
 */
public final class AssmacCipher {

    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";

    private static volatile byte[] key;
    
    // Performance optimization: reusing Cipher instances per thread avoids massive allocation 
    // and synchronization overheads during distributed table scans (e.g. 5M+ rows).
    private static final ThreadLocal<Cipher> CIPHER = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(TRANSFORMATION);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("AES/GCM/NoPadding is not available in this JVM", e);
        }
    });

    private AssmacCipher() {}

    /**
     * Sets the raw 32-byte AES-256 key used by {@link #decrypt(String)}.
     * Called once at plugin/config initialization time.
     */
    public static void setKey(byte[] rawKey) {
        if (rawKey == null || rawKey.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("AssmacCipher key must be exactly " + KEY_LENGTH_BYTES + " raw bytes");
        }
        key = rawKey.clone();
    }

    /**
     * Decrypts a base64(IV || ciphertext+tag) value produced by the Python ingestion side.
     * Throws on any failure (bad key, corrupted input, invalid tag) — callers are responsible
     * for fail-closed handling (e.g. catching and returning NULL from a scalar function).
     */
    public static String decrypt(String base64IvCiphertextTag) {
        byte[] currentKey = key;
        if (currentKey == null) {
            throw new IllegalStateException("AssmacCipher key has not been initialized");
        }
        if (base64IvCiphertextTag == null) {
            throw new IllegalArgumentException("Input must not be null");
        }

        byte[] decoded = Base64.getDecoder().decode(base64IvCiphertextTag);
        if (decoded.length <= IV_LENGTH_BYTES) {
            throw new IllegalArgumentException("Decoded input too short to contain an IV and ciphertext");
        }

        byte[] iv = new byte[IV_LENGTH_BYTES];
        byte[] ciphertextWithTag = new byte[decoded.length - IV_LENGTH_BYTES];
        System.arraycopy(decoded, 0, iv, 0, IV_LENGTH_BYTES);
        System.arraycopy(decoded, IV_LENGTH_BYTES, ciphertextWithTag, 0, ciphertextWithTag.length);

        try {
            Cipher cipher = CIPHER.get();
            SecretKeySpec keySpec = new SecretKeySpec(currentKey, KEY_ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
            byte[] plaintext = cipher.doFinal(ciphertextWithTag);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Re-thrown as a single unchecked type so callers only need to catch one thing.
            // The caller (scalar function) is responsible for fail-closed handling.
            throw new AssmacDecryptionException("Failed to decrypt assmac_act value", e);
        }
    }

    /**
     * Unchecked wrapper for any low-level crypto failure (bad key, corrupted ciphertext,
     * invalid GCM tag). Kept distinct from the underlying checked crypto exceptions so
     * callers can catch a single, clearly-named type.
     */
    public static class AssmacDecryptionException extends RuntimeException {
        public AssmacDecryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
