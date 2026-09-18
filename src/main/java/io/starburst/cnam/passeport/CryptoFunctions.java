package io.starburst.cnam.passeport;

import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

public final class CryptoFunctions {

    private CryptoFunctions() {}

    @ScalarFunction("decrypt_assmac")
    @Description("Decrypts an AES-256-GCM encrypted assmac_act value for authorized callers; returns NULL on any error (fail-closed)")
    @SqlNullable
    @SqlType(StandardTypes.VARCHAR)
    public static Slice decryptAssmac(@SqlType(StandardTypes.VARCHAR) Slice ciphertextSlice) {
        try {
            String plaintext = AssmacCipher.decrypt(ciphertextSlice.toStringUtf8());
            return Slices.utf8Slice(plaintext);
        } catch (Exception e) {
            return null;
        }
    }
}
