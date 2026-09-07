package io.starburst.cnam.passeport;

import io.trino.spi.Plugin;
import io.trino.spi.security.GroupProviderFactory;
import java.util.Collections;
import java.util.Set;

public class PasseportPlugin implements Plugin {

    @Override
    public Iterable<GroupProviderFactory> getGroupProviderFactories() {
        return Collections.singletonList(new PasseportGroupProviderFactory());
    }

    @Override
    public Set<Class<?>> getFunctions() {
        return Collections.singleton(PasseportFunctions.class);
    }
}
