package io.starburst.cnam.passeport;

import com.google.inject.Injector;
import io.airlift.bootstrap.Bootstrap;
import io.trino.spi.security.GroupProvider;
import io.trino.spi.security.GroupProviderFactory;
import java.util.Map;

public class PasseportGroupProviderFactory implements GroupProviderFactory {

    @Override
    public String getName() {
        return "passeport";
    }

    @Override
    public GroupProvider create(Map<String, String> config) {
        Bootstrap app = new Bootstrap(new PasseportModule());
        
        try {
            Injector injector = app
                .doNotInitializeLogging()
                .setRequiredConfigurationProperties(config)
                .initialize();
                
            return injector.getInstance(PasseportGroupProvider.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize PasseportGroupProvider", e);
        }
    }
}
