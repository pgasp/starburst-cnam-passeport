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
        
        return new PasseportGroupProvider(apiUrl, codeApplication, trustStorePath, trustStorePassword);
    }
}
