package io.starburst.cnam.passeport;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigSecuritySensitive;
import io.airlift.units.Duration;
import jakarta.validation.constraints.Min;

import java.util.concurrent.TimeUnit;

/**
 * Propriétés du plugin ({@code group-provider.properties}).
 *
 * <p>{@code passeport.api-url} et {@code passeport.code-application} sont obligatoires et n'ont volontairement
 * aucune valeur par défaut : leur absence est détectée au démarrage par {@link PasseportAuthService}.
 */
public class PasseportConfig {
    private String apiUrl;
    private String codeApplication;
    private String trustStorePath;
    private String trustStorePassword;
    private String rowFilterMappings = "";
    private String serviceAccount;
    private Duration cacheTtl = new Duration(15, TimeUnit.MINUTES);
    private int cacheMaxSize = 10_000;

    public String getApiUrl() { return apiUrl; }
    @Config("passeport.api-url")
    public PasseportConfig setApiUrl(String apiUrl) { this.apiUrl = apiUrl; return this; }

    public String getCodeApplication() { return codeApplication; }
    @Config("passeport.code-application")
    public PasseportConfig setCodeApplication(String codeApplication) { this.codeApplication = codeApplication; return this; }

    public String getTrustStorePath() { return trustStorePath; }
    @Config("passeport.trust-store-path")
    public PasseportConfig setTrustStorePath(String trustStorePath) { this.trustStorePath = trustStorePath; return this; }

    public String getTrustStorePassword() { return trustStorePassword; }
    @Config("passeport.trust-store-password")
    @ConfigSecuritySensitive
    public PasseportConfig setTrustStorePassword(String trustStorePassword) { this.trustStorePassword = trustStorePassword; return this; }

    public String getRowFilterMappings() { return rowFilterMappings; }
    @Config("passeport.row-filter-mappings")
    public PasseportConfig setRowFilterMappings(String rowFilterMappings) { this.rowFilterMappings = rowFilterMappings; return this; }

    public String getServiceAccount() { return serviceAccount; }
    @Config("passeport.service-account")
    public PasseportConfig setServiceAccount(String serviceAccount) { this.serviceAccount = serviceAccount; return this; }

    public Duration getCacheTtl() { return cacheTtl; }
    @Config("passeport.cache-ttl")
    public PasseportConfig setCacheTtl(Duration cacheTtl) { this.cacheTtl = cacheTtl; return this; }

    @Min(1)
    public int getCacheMaxSize() { return cacheMaxSize; }
    @Config("passeport.cache-max-size")
    public PasseportConfig setCacheMaxSize(int cacheMaxSize) { this.cacheMaxSize = cacheMaxSize; return this; }
}
