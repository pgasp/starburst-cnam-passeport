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
import java.util.Properties;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PasseportGroupProvider implements GroupProvider {

    private static final Logger log = Logger.getLogger(PasseportGroupProvider.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final String apiUrl;
    private final String codeApplication;
    private final HttpClient httpClient;
    
    private final boolean enablePerimetreWrite;
    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final String perimetreCatalog;
    private final String perimetreSchema;
    private final String perimetreTable;

    public PasseportGroupProvider(
            String apiUrl, 
            String codeApplication, 
            String trustStorePath, 
            String trustStorePassword,
            boolean enablePerimetreWrite,
            String jdbcUrl,
            String jdbcUser,
            String jdbcPassword,
            String perimetreCatalog,
            String perimetreSchema,
            String perimetreTable) {
        log.fine("Initializing PasseportGroupProvider with API URL: " + apiUrl + ", Code Application: " + codeApplication);
        this.apiUrl = apiUrl;
        this.codeApplication = codeApplication;
        
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
                
        // Configuration du TrustStore custom (pour l'autorité de certification IGCT)
        if (trustStorePath != null && !trustStorePath.isBlank()) {
            log.fine("Trust store path provided: " + trustStorePath + ". Attempting to configure custom SSLContext.");
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
        
        this.enablePerimetreWrite = enablePerimetreWrite;
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser;
        this.jdbcPassword = jdbcPassword;
        this.perimetreCatalog = perimetreCatalog;
        this.perimetreSchema = perimetreSchema;
        this.perimetreTable = perimetreTable;
        
        log.fine("PasseportGroupProvider initialization complete.");
    }

    @Override
    public Set<String> getGroups(String user) {
        log.fine("getGroups called for user: '" + user + "'");
        
        if (user == null || user.isBlank()) {
            log.fine("User is null or blank. Returning empty set.");
            return Collections.emptySet();
        }

        // 1. Vérifier si les droits sont déjà en cache
        PasseportAuthCache.AuthData cachedData = PasseportAuthCache.getInstance().get(user);
        if (cachedData != null) {
            log.fine("CACHE HIT for user " + user + ". Returning " + cachedData.getGroups().size() + " groups and " + cachedData.getPerimetres().size() + " perimetres from cache.");
            return cachedData.getGroups();
        }

        log.fine("CACHE MISS for user " + user + ". Calling Passeport API.");

        try {
            // Construction de l'URL : https://api.passeport.ramage/s1sem/habilitations/{upn}/{code_application}
            String url = String.format("%s/%s/%s", apiUrl, user, codeApplication);
            log.fine("Constructed Passeport API URL: " + url);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            log.fine("Sending GET request to Passeport API...");
            long startTime = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - startTime;
            
            log.fine("Received response from Passeport API with HTTP status code " + response.statusCode() + " in " + duration + "ms");

            if (response.statusCode() != 200) {
                log.warning("Passeport API returned status " + response.statusCode() + " for user " + user + ". Response body: " + response.body());
                return Collections.emptySet();
            }

            Set<String> groups = new HashSet<>();
            List<String> perimetres = new ArrayList<>();
            List<PerimetreRecord> records = new ArrayList<>();
            
            // Parsing de la réponse JSON
            log.fine("Parsing JSON response payload...");
            JsonNode root = mapper.readTree(response.body());
            
            // On suppose que l'API renvoie un tableau d'objets : [{"code_pa": "...", "perimetre": "..."}]
            if (root.isArray()) {
                log.fine("JSON payload is an array with " + root.size() + " elements.");
                for (JsonNode node : root) {
                    String codePa = null;
                    String p = null;
                    
                    if (node.has("code_pa") && !node.get("code_pa").isNull()) {
                        codePa = node.get("code_pa").asText();
                        groups.add(codePa);
                        log.finer("Extracted code_pa: " + codePa);
                    }
                    if (node.has("perimetre") && !node.get("perimetre").isNull()) {
                        // Ajouter seulement si non vide et non déjà présent pour éviter les doublons
                        p = node.get("perimetre").asText();
                        if (!p.isBlank() && !perimetres.contains(p)) {
                            perimetres.add(p);
                            log.finer("Extracted perimetre: " + p);
                        }
                    }
                    
                    if (codePa != null && p != null && !p.isBlank()) {
                        records.add(new PerimetreRecord(codePa, p));
                    }
                }
            } else {
                log.warning("Expected JSON array from Passeport API but received a different structure for user " + user);
            }
            
            log.fine("Resolution complete for user " + user + ". Found " + groups.size() + " unique code_pa(s) and " + perimetres.size() + " unique perimetre(s).");
            
            // Écriture JDBC fail-closed (périmètre uniquement) AVANT la mise en cache
            if (enablePerimetreWrite) {
                try {
                    writePerimetresToDb(user, records);
                } catch (SQLException e) {
                    log.log(Level.SEVERE, "Failed to write perimetres for user " + user + ". The user will have their groups but NO perimetres (RLS fail-closed).", e);
                    // On ne throw PAS l'exception pour permettre la remontée des groupes.
                }
            } else {
                log.fine("passeport.enable-perimetre-write=false. JDBC writing skipped for user " + user);
            }
            
            // 2. Mise à jour du cache partagé (Guava Cache - TTL 5min)
            log.fine("Updating Guava Auth Cache for user " + user + " with perimetres: " + perimetres);
            PasseportAuthCache.getInstance().put(user, groups, perimetres);
            
            // 3. Retour des groupes à Trino/SEP
            log.fine("Returning groups to Trino engine: " + groups);
            return groups;
            
        } catch (Exception e) {
            // Fail-closed : on catch toute erreur (réseau, timeout, parsing JSON)
            // On log et on retourne un set vide, sans propager l'exception pour ne pas casser la session.
            log.log(Level.SEVERE, "Failed to fetch groups from Passeport for user " + user + ". Applying fail-closed policy (returning empty groups).", e);
            return Collections.emptySet();
        }
    }
    
    private void writePerimetresToDb(String user, List<PerimetreRecord> records) throws SQLException {
        log.fine("Writing perimetres to Trino via JDBC for user " + user);
        
        Properties props = new Properties();
        props.setProperty("user", jdbcUser);
        if (jdbcPassword != null && !jdbcPassword.isEmpty()) {
            props.setProperty("password", jdbcPassword);
        }
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
            conn.setAutoCommit(false); // Enable transaction for delete + insert
            
            String fullTableName = perimetreCatalog + "." + perimetreSchema + "." + perimetreTable;
            
            // Delete existing records for the user
            String deleteSql = "DELETE FROM " + fullTableName + " WHERE upn = ?";
            try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                deleteStmt.setString(1, user);
                deleteStmt.executeUpdate();
            }
            
            // Insert new records
            if (!records.isEmpty()) {
                String insertSql = "INSERT INTO " + fullTableName + " (upn, code_pa, perimetre) VALUES (?, ?, ?)";
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    for (PerimetreRecord record : records) {
                        insertStmt.setString(1, user);
                        insertStmt.setString(2, record.codePa);
                        insertStmt.setString(3, record.perimetre);
                        insertStmt.addBatch();
                    }
                    insertStmt.executeBatch();
                }
            }
            
            conn.commit();
            log.fine("Successfully wrote " + records.size() + " perimetres to DB for user " + user);
        } catch (SQLException e) {
            log.log(Level.SEVERE, "JDBC Error while writing perimetres for user " + user, e);
            throw e; // will be caught in the main block and logged without breaking group resolution
        }
    }
    
    private static class PerimetreRecord {
        final String codePa;
        final String perimetre;
        
        PerimetreRecord(String codePa, String perimetre) {
            this.codePa = codePa;
            this.perimetre = perimetre;
        }
    }
}
