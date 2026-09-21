package io.starburst.cnam.passeport;

import io.trino.spi.security.GroupProvider;
import com.google.inject.Inject;
import java.util.Set;

public class PasseportGroupProvider implements GroupProvider {

    private final PasseportAuthService authService;

    @Inject
    public PasseportGroupProvider(PasseportAuthService authService) {
        this.authService = authService;
    }

    @Override
    public Set<String> getGroups(String user) {
        return authService.getGroups(user);
    }
}
