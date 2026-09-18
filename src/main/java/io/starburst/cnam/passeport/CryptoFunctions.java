package io.starburst.cnam.passeport;

import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;

public final class CryptoFunctions {

    private CryptoFunctions() {}

    @ScalarFunction("decrypt_assmac")
    @Description("Decrypts an AES-256-GCM encrypted assmac_act value for authorized callers; returns NULL on any error (fail-closed)")
    @SqlNullable
    @SqlType(StandardTypes.VARCHAR)
    public static String decryptAssmac(@SqlType(StandardTypes.VARCHAR) String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        try {
            return AssmacCipher.decrypt(ciphertext);
        } catch (Exception e) {
            return null;
        }
    }
}
