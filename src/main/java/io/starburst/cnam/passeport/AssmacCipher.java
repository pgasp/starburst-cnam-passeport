package io.starburst.cnam.passeport;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.GeneralSecurityException;

public final class AssmacCipher {

    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";

    private static volatile byte[] key;
    private static volatile SecretKeySpec secretKeySpec;
    
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

    public static void setKey(byte[] rawKey) {
        if (rawKey == null || rawKey.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("AssmacCipher key must be exactly " + KEY_LENGTH_BYTES + " raw bytes");
        }
        key = rawKey.clone();
        secretKeySpec = new SecretKeySpec(key, KEY_ALGORITHM);
    }

    public static String decrypt(String base64IvCiphertextTag) {
        if (base64IvCiphertextTag == null) {
            throw new IllegalArgumentException("Input must not be null");
        }
        Slice slice = Slices.utf8Slice(base64IvCiphertextTag);
        Slice decrypted = decrypt(slice);
        return decrypted.toStringUtf8();
    }

    public static Slice decrypt(Slice base64IvCiphertextTag) {
        SecretKeySpec currentKeySpec = secretKeySpec;
        if (currentKeySpec == null) {
            throw new IllegalStateException("AssmacCipher key has not been initialized");
        }
        if (base64IvCiphertextTag == null) {
            throw new IllegalArgumentException("Input must not be null");
        }

        ByteBuffer decoded = Base64.getDecoder().decode(base64IvCiphertextTag.toByteBuffer());
        int decodedLength = decoded.remaining();
        if (decodedLength <= IV_LENGTH_BYTES) {
            throw new IllegalArgumentException("Decoded input too short to contain an IV and ciphertext");
        }

        try {
            byte[] decodedBytes;
            int offset = 0;
            if (decoded.hasArray()) {
                decodedBytes = decoded.array();
                offset = decoded.arrayOffset() + decoded.position();
            } else {
                decodedBytes = new byte[decodedLength];
                decoded.get(decodedBytes);
            }

            Cipher cipher = CIPHER.get();
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, decodedBytes, offset, IV_LENGTH_BYTES);
            cipher.init(Cipher.DECRYPT_MODE, currentKeySpec, gcmSpec);
            
            byte[] plaintext = cipher.doFinal(decodedBytes, offset + IV_LENGTH_BYTES, decodedLength - IV_LENGTH_BYTES);
            return Slices.wrappedBuffer(plaintext);
        } catch (Exception e) {
            throw new AssmacDecryptionException("Failed to decrypt assmac_act value", e);
        }
    }

    public static class AssmacDecryptionException extends RuntimeException {
        public AssmacDecryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
