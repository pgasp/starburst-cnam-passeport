package io.starburst.cnam.passeport;

import com.google.common.base.Ticker;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.inject.Inject;
import io.trino.spi.StandardErrorCode;
import io.trino.spi.TrinoException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PasseportAuthService {
    private static final Logger log = Logger.getLogger(PasseportAuthService.class.getName());

    private final String baseUrl;
    private final String codeApplication;
    private final HttpClient httpClient;
    private final LoadingCache<String, AuthData> cache;

    public static class AuthData {
        private final Set<String> groups;
        private final List<String> perimetres;

        public AuthData(Set<String> groups, List<String> perimetres) {
            this.groups = Set.copyOf(groups);
            this.perimetres = List.copyOf(perimetres);
        }
        public Set<String> getGroups() { return groups; }
        public List<String> getPerimetres() { return perimetres; }
    }

    private static volatile PasseportAuthService globalInstance;

    @Inject
    public PasseportAuthService(PasseportConfig config) {
        this(config, Ticker.systemTicker());
    }

    /** Constructeur de test : le {@link Ticker} permet de simuler l'écoulement du TTL du cache. */
    PasseportAuthService(PasseportConfig config, Ticker ticker) {
        // Configuration obligatoire, sans valeur par défaut : fail closed dès le démarrage.
        this.baseUrl = requireBaseUrl(config.getApiUrl());
        this.codeApplication = requireProperty("passeport.code-application", config.getCodeApplication()).strip();

        // Le validateur @MinDuration d'airlift units 1.0 repose sur javax.validation, ignoré par hibernate-validator 8 :
        // la borne minimale du TTL est donc contrôlée ici.
        if (config.getCacheTtl() == null || config.getCacheTtl().toMillis() < 1000) {
            throw new TrinoException(StandardErrorCode.CONFIGURATION_INVALID,
                    "Invalid property 'passeport.cache-ttl': must be at least 1s");
        }

        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));

        if (config.getTrustStorePath() != null && !config.getTrustStorePath().isBlank()) {
            try {
                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                char[] password = config.getTrustStorePassword() != null ? config.getTrustStorePassword().toCharArray() : null;
                try (InputStream is = new FileInputStream(config.getTrustStorePath())) {
                    trustStore.load(is, password);
                }
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
                clientBuilder.sslContext(sslContext);
            } catch (Exception e) {
                log.log(Level.SEVERE, "Failed to configure custom TLS trust store.", e);
                throw new RuntimeException("TLS configuration failed", e);
            }
        }

        this.httpClient = clientBuilder.build();

        // Cache Guava (LoadingCache : un seul appel API par utilisateur même en cas de requêtes concurrentes).
        // Une exception du chargeur n'est jamais mise en cache : l'échec n'est pas mémorisé,
        // la requête suivante réinterroge l'API.
        this.cache = CacheBuilder.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(config.getCacheTtl().toMillis(), TimeUnit.MILLISECONDS)
                .maximumSize(config.getCacheMaxSize())
                .build(new CacheLoader<String, AuthData>() {
                    @Override
                    public AuthData load(String key) throws Exception {
                        return fetchFromApi(key);
                    }
                });

        globalInstance = this;
    }

    public static PasseportAuthService getGlobalInstance() {
        return globalInstance;
    }

    private AuthData fetchFromApi(String user) {
        log.fine("Fetching Passeport data for user: " + user);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(buildUri(baseUrl, user, codeApplication))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warning("Passeport API returned status " + response.statusCode() + " for user " + user);
                throw new TrinoException(StandardErrorCode.GENERIC_INTERNAL_ERROR, "Passeport API returned status " + response.statusCode());
            }

            return PasseportResponseParser.parse(response.body(), codeApplication);
        } catch (TrinoException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrinoException(StandardErrorCode.GENERIC_INTERNAL_ERROR, "Interrupted while fetching data from Passeport API", e);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to fetch groups from Passeport for user " + user, e);
            throw new TrinoException(StandardErrorCode.GENERIC_INTERNAL_ERROR, "Failed to fetch data from Passeport API", e);
        }
    }

    /**
     * URL appelée : {@code <api-url>/<upn>/<code-application>}. L'UPN et le code application sont encodés comme
     * segments de chemin : aucun caractère reçu ne peut ajouter de segment, de paramètre ou de fragment à l'URL.
     */
    static URI buildUri(String baseUrl, String user, String codeApplication) {
        return URI.create(baseUrl + "/" + encodePathSegment(user) + "/" + encodePathSegment(codeApplication));
    }

    /**
     * Encodage en pourcent (UTF-8) de tout caractère hors {@code A-Z a-z 0-9 - . _ ~ @}. Le {@code @} (UPN) est un
     * caractère valide d'un segment de chemin (RFC 3986) et reste tel quel ; un segment fait uniquement de points
     * est encodé pour ne pas être lu comme {@code .} ou {@code ..}.
     */
    static String encodePathSegment(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '@';
            if (safe) {
                out.append((char) c);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        String encoded = out.toString();
        return encoded.chars().allMatch(ch -> ch == '.') ? encoded.replace(".", "%2E") : encoded;
    }

    private static String requireProperty(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new TrinoException(StandardErrorCode.CONFIGURATION_INVALID,
                    "Missing required property '" + name + "' (no default value): set it in group-provider.properties");
        }
        return value;
    }

    /** Valide l'URL de base et retire les {@code /} finaux. Fail closed : une URL inexploitable bloque le démarrage. */
    static String requireBaseUrl(String apiUrl) {
        String value = requireProperty("passeport.api-url", apiUrl).strip();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new URISyntaxException(value, "expected an absolute http(s) URL without query or fragment");
            }
        } catch (URISyntaxException e) {
            throw new TrinoException(StandardErrorCode.CONFIGURATION_INVALID,
                    "Invalid property 'passeport.api-url': expected an absolute http(s) URL without query or fragment");
        }
        return value;
    }

    public Set<String> getGroups(String user) {
        if (user == null || user.isBlank()) return Collections.emptySet();
        try {
            return cache.getUnchecked(user).getGroups();
        } catch (Exception e) {
            throw new TrinoException(StandardErrorCode.GENERIC_INTERNAL_ERROR, "Error retrieving groups for user " + user, e);
        }
    }

    public List<String> getPerimetres(String user) {
        if (user == null || user.isBlank()) return Collections.emptyList();
        try {
            return cache.getUnchecked(user).getPerimetres();
        } catch (Exception e) {
            throw new TrinoException(StandardErrorCode.GENERIC_INTERNAL_ERROR, "Error retrieving perimetres for user " + user, e);
        }
    }

    public void flush(String user) {
        if (user == null) {
            cache.invalidateAll();
        } else {
            cache.invalidate(user);
        }
    }
}
