package io.starburst.cnam.passeport;

import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CryptoFunctions {

    private static final Logger log = Logger.getLogger(CryptoFunctions.class.getName());

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
            // Un exception catch-all silencieux en sécurité est un anti-pattern. 
            // On loggue en debug/fine pour ne pas flood les logs de production, mais garder une trace.
            log.log(Level.FINE, "Failed to decrypt ciphertext. Returning NULL.", e);
            return null;
        }
    }
}
