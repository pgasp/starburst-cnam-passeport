package io.starburst.cnam.passeport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.trino.spi.StandardErrorCode;
import io.trino.spi.TrinoException;
import com.google.inject.Inject;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PasseportAuthService {
    private static final Logger log = Logger.getLogger(PasseportAuthService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    private final PasseportConfig config;
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
        this.config = config;
        
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
        
        // Cache configuration using Guava LoadingCache for safe concurrent fetching
        this.cache = CacheBuilder.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(15))
                .maximumSize(10000)
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
            String url = String.format("%s/%s/%s", config.getApiUrl(), user, config.getCodeApplication());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warning("Passeport API returned status " + response.statusCode() + " for user " + user);
                throw new TrinoException(StandardErrorCode.GENERIC_INTERNAL_ERROR, "Passeport API returned status " + response.statusCode());
            }

            Set<String> groups = new HashSet<>();
            List<String> perimetres = new ArrayList<>();
            
            JsonNode root = mapper.readTree(response.body());
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if (node.has("code_pa") && !node.get("code_pa").isNull()) {
                        groups.add(node.get("code_pa").asText());
                    }
                    if (node.has("perimetre") && !node.get("perimetre").isNull()) {
                        String p = node.get("perimetre").asText();
                        if (!p.isBlank() && !perimetres.contains(p)) {
                            perimetres.add(p);
                        }
                    }
                }
            } else {
                throw new TrinoException(StandardErrorCode.GENERIC_INTERNAL_ERROR, "Expected JSON array from Passeport API");
            }
            return new AuthData(groups, perimetres);
        } catch (TrinoException e) {
            throw e;
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to fetch groups from Passeport for user " + user, e);
            throw new TrinoException(StandardErrorCode.GENERIC_INTERNAL_ERROR, "Failed to fetch data from Passeport API", e);
        }
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
