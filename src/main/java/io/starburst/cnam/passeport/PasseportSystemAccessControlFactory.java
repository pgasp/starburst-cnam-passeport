package io.starburst.cnam.passeport;

import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemAccessControlFactory;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class PasseportSystemAccessControlFactory implements SystemAccessControlFactory {

    private static final Logger log = Logger.getLogger(PasseportSystemAccessControlFactory.class.getName());

    @Override
    public String getName() {
        return "passeport";
    }

    @Override
    public SystemAccessControl create(Map<String, String> config, SystemAccessControlFactory.SystemAccessControlContext context) {
        // Lecture de la configuration : passeport.row-filter-mappings
        // Format attendu: catalog.schema.table1:colonne1,catalog.schema.table2:colonne2
        String mappingsConfig = config.getOrDefault("passeport.row-filter-mappings", "");
        Map<CatalogSchemaTableName, String> rowFilterMappings = new HashMap<>();

        if (!mappingsConfig.isEmpty()) {
            for (String mapping : mappingsConfig.split(",")) {
                String[] parts = mapping.split(":");
                if (parts.length == 2) {
                    String[] tableParts = parts[0].split("\\.");
                    if (tableParts.length == 3) {
                        rowFilterMappings.put(
                                new CatalogSchemaTableName(tableParts[0], tableParts[1], tableParts[2]),
                                parts[1]
                        );
                    } else {
                        log.warning("Invalid row filter mapping skipped (expected catalog.schema.table format for table part): " + mapping);
                    }
                } else {
                    log.warning("Invalid row filter mapping skipped (expected table:column format): " + mapping);
                }
            }
        }

        // Configuration du compte de service (optionnel) pour exécuter le Row Filter en mode Definer.
        // Si non renseigné, le filtre s'exécutera en mode Invoker (avec les droits de l'utilisateur).
        String serviceAccount = config.get("passeport.service-account");

        // Configuration obsolète mais conservée pour éviter des erreurs au démarrage si présente dans le fichier
        config.getOrDefault("passeport.perimetre-catalog", "system");
        config.getOrDefault("passeport.perimetre-schema", "passeport");

        // Clé AES-256 (base64, 32 octets bruts) pour decrypt_assmac(). Optionnelle : si absente,
        // la fonction decrypt_assmac() échouera (fail-closed, retourne NULL) faute de clé.
        String assmacEncryptionKey = config.getOrDefault("passeport.assmac-encryption-key", "");
        if (!assmacEncryptionKey.isEmpty()) {
            byte[] rawKey;
            try {
                rawKey = Base64.getDecoder().decode(assmacEncryptionKey);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("passeport.assmac-encryption-key must be valid base64", e);
            }
            if (rawKey.length != 32) {
                throw new IllegalArgumentException("passeport.assmac-encryption-key must decode to exactly 32 bytes (AES-256), got " + rawKey.length);
            }
            AssmacCipher.setKey(rawKey);
        }

        return new PasseportSystemAccessControl(rowFilterMappings, serviceAccount);
    }
}
