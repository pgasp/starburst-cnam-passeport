package io.starburst.cnam.passeport;

import com.google.inject.Inject;
import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemSecurityContext;
import io.trino.spi.security.ViewExpression;

import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PasseportSystemAccessControl implements SystemAccessControl {

    private final Map<CatalogSchemaTableName, String> rowFilterMappings;
    private final String serviceAccount;
    private final PasseportAuthService authService;

    @Inject
    public PasseportSystemAccessControl(PasseportConfig config, PasseportAuthService authService) {
        this.authService = authService;
        this.serviceAccount = config.getServiceAccount();
        this.rowFilterMappings = new HashMap<>();

        String mappingsConfig = config.getRowFilterMappings();
        if (mappingsConfig != null && !mappingsConfig.isEmpty()) {
            for (String mapping : mappingsConfig.split(",")) {
                String[] parts = mapping.split(":");
                if (parts.length == 2) {
                    String[] tableParts = parts[0].split("\\.");
                    if (tableParts.length == 3) {
                        rowFilterMappings.put(
                                new CatalogSchemaTableName(tableParts[0], tableParts[1], tableParts[2]),
                                parts[1]
                        );
                    }
                }
            }
        }
        
        // Initialiser la clef AES globalement si on l'a configuree. 
        // Note: AssmacCipher est statique dans le design actuel, c'est acceptable tant qu'il y a un seul process Trino.
        String assmacEncryptionKey = config.getAssmacEncryptionKey();
        if (assmacEncryptionKey != null && !assmacEncryptionKey.isEmpty()) {
            byte[] rawKey;
            try {
                rawKey = Base64.getDecoder().decode(assmacEncryptionKey);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("passeport.assmac-encryption-key must be valid base64", e);
            }
            if (rawKey.length != 32) {
                throw new IllegalArgumentException("passeport.assmac-encryption-key must decode to exactly 32 bytes (AES-256), got " + rawKey.length);
            }
            AssmacCipher.setKey(rawKey);
        }
    }

    @Override
    public List<ViewExpression> getRowFilters(SystemSecurityContext context, CatalogSchemaTableName tableName) {
        String filterColumn = rowFilterMappings.get(tableName);
        
        if (filterColumn != null) {
            String user = context.getIdentity().getUser();
            
            // Delegate to the shared auth service
            List<String> perimetres = authService.getPerimetres(user);
            
            String expression;
            if (perimetres == null || perimetres.isEmpty()) {
                expression = "FALSE";
            } else {
                String inList = perimetres.stream()
                        .map(p -> "'" + p.replace("'", "''") + "'")
                        .collect(Collectors.joining(", "));
                
                expression = String.format("%s IN (%s)", filterColumn, inList);
            }
            
            ViewExpression.Builder builder = ViewExpression.builder().expression(expression);
            
            if (serviceAccount != null && !serviceAccount.isEmpty()) {
                builder.identity(serviceAccount);
            }
            
            return Collections.singletonList(builder.build());
        }
        return Collections.emptyList();
    }
}
