package io.starburst.cnam.passeport;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class HashFunctions {

    private static final byte[] HEX_UPPER = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
    private static final byte SEPARATOR = (byte) '|';

    // MessageDigest n'est pas thread-safe : une instance par thread évite de le ré-allouer à chaque ligne.
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    });

    private HashFunctions() {}

    @ScalarFunction("hash_user_salt")
    @Description("Equivalent of to_hex(sha256(to_utf8(value || '|' || salt))): SHA-256 hex digest in upper case. NULL in, NULL out. Usage: hash_user_salt(benidf_act, current_user)")
    @SqlType(StandardTypes.VARCHAR)
    public static Slice hashUserSalt(@SqlType(StandardTypes.VARCHAR) Slice value, @SqlType(StandardTypes.VARCHAR) Slice salt)
    {
        MessageDigest digest = SHA256.get();
        digest.reset();
        digest.update(value.toByteBuffer());
        digest.update(SEPARATOR);
        digest.update(salt.toByteBuffer());
        byte[] hash = digest.digest();

        byte[] hex = new byte[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            int v = hash[i] & 0xFF;
            hex[i * 2] = HEX_UPPER[v >>> 4];
            hex[i * 2 + 1] = HEX_UPPER[v & 0x0F];
        }
        return Slices.wrappedBuffer(hex);
    }
}
