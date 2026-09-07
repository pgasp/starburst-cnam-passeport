package io.starburst.cnam.passeport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.trino.spi.security.GroupProvider;

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

public class PasseportGroupProvider implements GroupProvider {

    private static final Logger log = Logger.getLogger(PasseportGroupProvider.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final String apiUrl;
    private final String codeApplication;
    private final PasseportPerimetreCache cache;
    private final HttpClient httpClient;

    public PasseportGroupProvider(String apiUrl, String codeApplication, String trustStorePath, String trustStorePassword) {
        this.apiUrl = apiUrl;
        this.codeApplication = codeApplication;
        this.cache = PasseportPerimetreCache.getInstance();
        
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
                
        // Configuration du TrustStore custom (pour l'autorité de certification IGCT)
        if (trustStorePath != null && !trustStorePath.isBlank()) {
            try {
                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                char[] password = trustStorePassword != null ? trustStorePassword.toCharArray() : null;
                try (InputStream is = new FileInputStream(trustStorePath)) {
                    trustStore.load(is, password);
                }
                
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
                
                clientBuilder.sslContext(sslContext);
                log.info("Custom TLS trust store configured successfully from " + trustStorePath);
            } catch (Exception e) {
                log.log(Level.SEVERE, "Failed to configure custom TLS trust store from " + trustStorePath + ". Group provider will fail to start.", e);
                throw new RuntimeException("TLS configuration failed", e);
            }
        } else {
            log.warning("No passeport.trust-store-path configured. The HTTP client will use the default JVM trust store.");
        }

        this.httpClient = clientBuilder.build();
    }

    @Override
    public Set<String> getGroups(String user) {
        if (user == null || user.isBlank()) {
            return Collections.emptySet();
        }

        try {
            // Construction de l'URL : https://api.passeport.ramage/s1sem/habilitations/{upn}/{code_application}
            String url = String.format("%s/%s/%s", apiUrl, user, codeApplication);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warning("Passeport API returned status " + response.statusCode() + " for user " + user);
                return Collections.emptySet();
            }

            Set<String> groups = new HashSet<>();
            List<String> perimetres = new ArrayList<>();
            
            // Parsing de la réponse JSON
            JsonNode root = mapper.readTree(response.body());
            
            // On suppose que l'API renvoie un tableau d'objets : [{"code_pa": "...", "perimetre": "..."}]
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if (node.has("code_pa") && !node.get("code_pa").isNull()) {
                        groups.add(node.get("code_pa").asText());
                    }
                    if (node.has("perimetre") && !node.get("perimetre").isNull()) {
                        // Ajouter seulement si non vide et non déjà présent pour éviter les doublons
                        String p = node.get("perimetre").asText();
                        if (!p.isBlank() && !perimetres.contains(p)) {
                            perimetres.add(p);
                        }
                    }
                }
            }
            
            // 1. Mise à jour du cache partagé
            cache.updatePerimetre(user, perimetres);
            
            // 2. Retour des groupes à Trino/SEP
            return groups;
            
        } catch (Exception e) {
            // Fail-closed : on catch toute erreur (réseau, timeout, parsing JSON)
            // On log et on retourne un set vide, sans propager l'exception pour ne pas casser la session.
            log.log(Level.SEVERE, "Failed to fetch groups from Passeport for user " + user, e);
            return Collections.emptySet();
        }
    }
}
