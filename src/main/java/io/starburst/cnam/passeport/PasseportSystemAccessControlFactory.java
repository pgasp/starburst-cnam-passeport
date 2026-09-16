package io.starburst.cnam.passeport;

import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemAccessControlFactory;

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

        String perimetreCatalog = config.getOrDefault("passeport.perimetre-catalog", "system");
        String perimetreSchema = config.getOrDefault("passeport.perimetre-schema", "passeport");
        String perimetreTable = "user_perimetre"; // Hardcodé selon les conventions

        return new PasseportSystemAccessControl(
                rowFilterMappings, 
                perimetreCatalog, 
                perimetreSchema, 
                perimetreTable
        );
    }
}
