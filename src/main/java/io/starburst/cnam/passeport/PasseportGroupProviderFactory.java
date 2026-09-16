package io.starburst.cnam.passeport;

import io.trino.spi.security.GroupProvider;
import io.trino.spi.security.GroupProviderFactory;
import java.util.Map;

public class PasseportGroupProviderFactory implements GroupProviderFactory {

    @Override
    public String getName() {
        return "cnam-passeport";
    }

    @Override
    public GroupProvider create(Map<String, String> config) {
        String codeApplication = config.getOrDefault("passeport.code-application", "PASSEPORT_DEFAULT");
        String apiUrl = config.getOrDefault("passeport.api-url", "https://api.passeport.ramage/s1sem/habilitations");
        String trustStorePath = config.get("passeport.trust-store-path");
        String trustStorePassword = config.get("passeport.trust-store-password");
        
        boolean enablePerimetreWrite = Boolean.parseBoolean(config.getOrDefault("passeport.enable-perimetre-write", "false"));
        String jdbcUrl = config.getOrDefault("passeport.jdbc-url", "");
        String jdbcUser = config.getOrDefault("passeport.jdbc-user", "");
        String jdbcPassword = config.getOrDefault("passeport.jdbc-password", "");
        String perimetreCatalog = config.getOrDefault("passeport.perimetre-catalog", "system");
        String perimetreSchema = config.getOrDefault("passeport.perimetre-schema", "passeport");
        String perimetreTable = "user_perimetre"; // Hardcodé selon les conventions

        if (enablePerimetreWrite) {
            if (jdbcUrl == null || jdbcUrl.isBlank() || jdbcUser == null || jdbcUser.isBlank()) {
                throw new IllegalArgumentException("passeport.enable-perimetre-write=true requires passeport.jdbc-url and passeport.jdbc-user to be set");
            }
        }

        return new PasseportGroupProvider(
                apiUrl, 
                codeApplication, 
                trustStorePath, 
                trustStorePassword,
                enablePerimetreWrite,
                jdbcUrl,
                jdbcUser,
                jdbcPassword,
                perimetreCatalog,
                perimetreSchema,
                perimetreTable
        );
    }
}
