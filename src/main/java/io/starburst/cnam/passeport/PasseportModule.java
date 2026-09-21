package io.starburst.cnam.passeport;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Scopes;
import static io.airlift.configuration.ConfigBinder.configBinder;

public class PasseportModule implements Module {
    @Override
    public void configure(Binder binder) {
        configBinder(binder).bindConfig(PasseportConfig.class);
        binder.bind(PasseportAuthService.class).in(Scopes.SINGLETON);
        binder.bind(PasseportGroupProvider.class).in(Scopes.SINGLETON);
        binder.bind(PasseportSystemAccessControl.class).in(Scopes.SINGLETON);
    }
}
