package io.starburst.cnam.passeport;

import io.trino.spi.Plugin;
import io.trino.spi.security.GroupProviderFactory;
import io.trino.spi.security.SystemAccessControlFactory;
import java.util.Collections;
import java.util.Set;

public class PasseportPlugin implements Plugin {

    @Override
    public Iterable<GroupProviderFactory> getGroupProviderFactories() {
        return Collections.singletonList(new PasseportGroupProviderFactory());
    }

    @Override
    public Iterable<SystemAccessControlFactory> getSystemAccessControlFactories() {
        return Collections.singletonList(new PasseportSystemAccessControlFactory());
    }

    @Override
    public Set<Class<?>> getFunctions() {
        return Set.of(
            PasseportFunctions.class,
            CryptoFunctions.class
        );
    }
}
