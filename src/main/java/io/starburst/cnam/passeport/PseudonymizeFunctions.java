package io.starburst.cnam.passeport;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;
import java.util.function.UnaryOperator;

/**
 * UDF {@code pseudonymize(varchar) -> varchar} : HMAC-SHA256 à clé secrète, voir {@link Pseudonymizer}.
 *
 * <p>La clé est lue dans l'environnement du processus au premier appel puis conservée pour la durée de vie de la JVM :
 * une rotation de clé impose un redémarrage du coordinator et des workers. Un échec de lecture n'est pas mis en cache.
 * Le coordinator doit aussi porter la clé : Trino y évalue (constant folding) les appels à arguments constants.
 */
public final class PseudonymizeFunctions {

    private static volatile UnaryOperator<String> environment = System::getenv;
    private static volatile Pseudonymizer pseudonymizer;

    private PseudonymizeFunctions() {}

    @ScalarFunction("pseudonymize")
    @Description("Deterministic keyed pseudonym: upper-case hex HMAC-SHA256 of upper(trim(value)), key read from the PSEUDONYMIZATION_HMAC_KEY environment variable. NULL in, NULL out (also for a blank value). Fails if the key is not configured.")
    @SqlType(StandardTypes.VARCHAR)
    @SqlNullable
    public static Slice pseudonymize(@SqlType(StandardTypes.VARCHAR) Slice value)
    {
        String pseudonym = currentPseudonymizer().pseudonymize(value.toStringUtf8());
        return pseudonym == null ? null : Slices.utf8Slice(pseudonym);
    }

    private static Pseudonymizer currentPseudonymizer()
    {
        Pseudonymizer current = pseudonymizer;
        if (current == null) {
            synchronized (PseudonymizeFunctions.class) {
                current = pseudonymizer;
                if (current == null) {
                    current = Pseudonymizer.fromEnvironment(environment);
                    pseudonymizer = current;
                }
            }
        }
        return current;
    }

    /** Hook de test : remplace la source d'environnement et vide le cache de clé. */
    static synchronized void useEnvironment(UnaryOperator<String> newEnvironment)
    {
        environment = newEnvironment;
        pseudonymizer = null;
    }
}
