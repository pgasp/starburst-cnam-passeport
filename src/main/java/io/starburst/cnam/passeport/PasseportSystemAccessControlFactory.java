package io.starburst.cnam.passeport;

import com.google.inject.Injector;
import io.airlift.bootstrap.Bootstrap;
import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemAccessControlFactory;
import java.util.Map;

public class PasseportSystemAccessControlFactory implements SystemAccessControlFactory {

    @Override
    public String getName() {
        return "passeport";
    }

    @Override
    public SystemAccessControl create(Map<String, String> config, SystemAccessControlFactory.SystemAccessControlContext context) {
        Bootstrap app = new Bootstrap(new PasseportModule());
        
        try {
            Injector injector = app
                .doNotInitializeLogging()
                .setRequiredConfigurationProperties(config)
                .initialize();
                
            return injector.getInstance(PasseportSystemAccessControl.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize PasseportSystemAccessControl", e);
        }
    }
}
