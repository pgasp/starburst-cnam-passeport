package io.starburst.cnam.passeport;

import io.airlift.configuration.Config;

public class PasseportConfig {
    private String apiUrl = "https://api.passeport.ramage/s1sem/habilitations";
    private String codeApplication = "PASSEPORT_DEFAULT";
    private String trustStorePath;
    private String trustStorePassword;
    private String rowFilterMappings = "";
    private String serviceAccount;

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
    public PasseportConfig setTrustStorePassword(String trustStorePassword) { this.trustStorePassword = trustStorePassword; return this; }

    public String getRowFilterMappings() { return rowFilterMappings; }
    @Config("passeport.row-filter-mappings")
    public PasseportConfig setRowFilterMappings(String rowFilterMappings) { this.rowFilterMappings = rowFilterMappings; return this; }

    public String getServiceAccount() { return serviceAccount; }
    @Config("passeport.service-account")
    public PasseportConfig setServiceAccount(String serviceAccount) { this.serviceAccount = serviceAccount; return this; }

}
