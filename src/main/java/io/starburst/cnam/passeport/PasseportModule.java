package io.starburst.cnam.passeport;

import com.google.inject.Binder;
import com.google.inject.CreationException;
import com.google.inject.Module;
import com.google.inject.Scopes;
import com.google.inject.spi.Message;
import io.trino.spi.TrinoException;

import static io.airlift.configuration.ConfigBinder.configBinder;

public class PasseportModule implements Module {
    @Override
    public void configure(Binder binder) {
        configBinder(binder).bindConfig(PasseportConfig.class);
        binder.bind(PasseportAuthService.class).in(Scopes.SINGLETON);
        binder.bind(PasseportGroupProvider.class).in(Scopes.SINGLETON);
        binder.bind(PasseportSystemAccessControl.class).in(Scopes.SINGLETON);
    }

    /**
     * Message d'échec de démarrage : le message de notre propre {@link TrinoException} (propriété manquante ou
     * invalide) quand Guice l'a encapsulée, sinon celui de l'exception reçue (erreur de configuration airlift).
     * On évite {@link CreationException#getMessage()} quand c'est possible : il est très verbeux.
     */
    static String startupFailureMessage(String component, Exception failure) {
        String prefix = "Failed to initialize " + component + ": ";
        if (failure instanceof CreationException creation) {
            for (Message message : creation.getErrorMessages()) {
                if (message.getCause() instanceof TrinoException cause) {
                    return prefix + cause.getMessage();
                }
            }
        }
        for (Throwable t = failure; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof TrinoException) {
                return prefix + t.getMessage();
            }
        }
        return prefix + failure.getMessage();
    }
}
