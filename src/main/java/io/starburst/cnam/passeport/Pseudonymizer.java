package io.starburst.cnam.passeport;

import io.trino.spi.TrinoException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.UnaryOperator;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;

/**
 * Pseudonymisation déterministe : HMAC-SHA256 à clé secrète (javax.crypto.Mac, aucune crypto maison).
 *
 * <p>Normalisation d'entrée : {@link String#strip()} puis {@link String#toUpperCase(Locale)} en {@link Locale#ROOT}
 * (les n° techniques n'ont pas de casse significative ; deux écritures du même matricule donnent le même pseudonyme).
 * Une entrée vide après normalisation donne {@code null} : sinon toutes les valeurs vides partageraient un pseudonyme
 * constant et seraient jointes entre elles.
 *
 * <p>Sortie : les 32 octets du HMAC en hexadécimal majuscule (64 caractères), comme {@code hash_user_salt}.
 *
 * <p>La clé n'apparaît jamais dans un message d'erreur, une trace ou un log. Fail closed : clé absente ou invalide
 * (non hexadécimale, moins de {@value #MIN_KEY_BYTES} octets) = erreur explicite, jamais de valeur en clair
 * ni de hash sans clé.
 */
public final class Pseudonymizer {

    /** Nom de la variable d'environnement portant la clé (hexadécimal), injectée par secret K8s (envFrom). */
    public static final String KEY_ENV_VARIABLE = "PSEUDONYMIZATION_HMAC_KEY";
    /** Taille minimale de la clé : 256 bits, la taille du bloc de sortie de SHA-256. */
    public static final int MIN_KEY_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private final SecretKeySpec key;
    // Mac n'est pas thread-safe : une instance par thread, initialisée avec la clé une seule fois.
    // doFinal() remet le Mac dans l'état initialisé avec la même clé.
    private final ThreadLocal<Mac> mac = ThreadLocal.withInitial(this::newMac);

    /** Clé brute non vide (les vecteurs RFC 4231 utilisent des clés plus courtes que {@link #MIN_KEY_BYTES}). */
    Pseudonymizer(byte[] rawKey)
    {
        if (rawKey == null || rawKey.length == 0) {
            throw new IllegalArgumentException("HMAC key must not be empty");
        }
        this.key = new SecretKeySpec(rawKey.clone(), ALGORITHM);
    }

    /** Lit la clé dans l'environnement ; fail closed (TrinoException CONFIGURATION_INVALID) si absente ou invalide. */
    static Pseudonymizer fromEnvironment(UnaryOperator<String> environment)
    {
        String value = environment.apply(KEY_ENV_VARIABLE);
        if (value == null || value.isBlank()) {
            throw new TrinoException(CONFIGURATION_INVALID,
                    "Pseudonymization key is not configured: environment variable " + KEY_ENV_VARIABLE
                            + " is missing or empty on this node (check the envFrom secret of coordinator and workers)");
        }
        byte[] raw;
        try {
            raw = HEX.parseHex(value.strip());
        }
        catch (IllegalArgumentException e) {
            // Pas de cause chaînée : le message de l'exception JDK pourrait citer la valeur.
            throw invalidKey();
        }
        if (raw.length < MIN_KEY_BYTES) {
            throw invalidKey();
        }
        return new Pseudonymizer(raw);
    }

    private static TrinoException invalidKey()
    {
        return new TrinoException(CONFIGURATION_INVALID,
                "Pseudonymization key in environment variable " + KEY_ENV_VARIABLE
                        + " is invalid: expected at least " + MIN_KEY_BYTES + " bytes encoded as hexadecimal");
    }

    /** Pseudonyme d'une valeur après normalisation ; {@code null} si la valeur est vide une fois normalisée. */
    String pseudonymize(String value)
    {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return null;
        }
        return hmacHex(normalized.getBytes(StandardCharsets.UTF_8));
    }

    static String normalize(String value)
    {
        return value.strip().toUpperCase(Locale.ROOT);
    }

    /** HMAC-SHA256 brut (sans normalisation) en hexadécimal majuscule. */
    String hmacHex(byte[] data)
    {
        return HEX.formatHex(mac.get().doFinal(data));
    }

    private Mac newMac()
    {
        try {
            Mac instance = Mac.getInstance(ALGORITHM);
            instance.init(key);
            return instance;
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }

    @Override
    public String toString()
    {
        return "Pseudonymizer[HmacSHA256]"; // jamais la clé
    }
}
