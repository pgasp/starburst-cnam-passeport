package io.starburst.cnam.passeport;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CryptoFunctions {

    private static final Logger log = Logger.getLogger(CryptoFunctions.class.getName());

    // CONSTANTE_SALT fixée dans le code
    private static final byte[] SALT_BYTES = "CnamPasseportSecretSalt2026".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HEX_ARRAY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    // ThreadLocal pour réutiliser MessageDigest (pas thread-safe) sans le ré-allouer par ligne
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    });

    private CryptoFunctions() {}

    @ScalarFunction("hash_salt")
    @Description("Hashes the input with a constant salt using SHA-256")
    @SqlNullable
    @SqlType(StandardTypes.VARCHAR)
    public static Slice hashSalt(@SqlType(StandardTypes.VARCHAR) Slice input) {
        if (input == null) {
            return null;
        }
        
        try {
            MessageDigest digest = SHA256.get();
            digest.reset();
            
            // On passe le ByteBuffer direct sans conversion String (zéro allocation supplémentaire)
            digest.update(input.toByteBuffer());
            digest.update(SALT_BYTES);
            
            byte[] hash = digest.digest();
            
            // Conversion binaire vers chaîne hexadécimale (Slice) de manière optimisée (sans classe intermédiaire)
            byte[] hexChars = new byte[hash.length * 2];
            for (int j = 0; j < hash.length; j++) {
                int v = hash[j] & 0xFF;
                hexChars[j * 2] = HEX_ARRAY[v >>> 4];
                hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
            }
            
            return Slices.wrappedBuffer(hexChars);
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to hash input", e);
            return null;
        }
    }

    @ScalarFunction("decrypt_assmac")
    @Description("Decrypts an AES-256-GCM encrypted assmac_act value for authorized callers; returns NULL on any error (fail-closed)")
    @SqlNullable
    @SqlType(StandardTypes.VARCHAR)
    public static Slice decryptAssmac(@SqlType(StandardTypes.VARCHAR) Slice ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        try {
            return Slices.utf8Slice(AssmacCipher.decrypt(ciphertext.toStringUtf8()));
        } catch (Exception e) {
            log.log(Level.FINE, "Failed to decrypt ciphertext. Returning NULL.", e);
            return null;
        }
    }
}
