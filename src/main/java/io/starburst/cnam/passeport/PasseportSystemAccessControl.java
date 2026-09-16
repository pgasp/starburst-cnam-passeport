package io.starburst.cnam.passeport;

import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemSecurityContext;
import io.trino.spi.security.ViewExpression;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PasseportSystemAccessControl implements SystemAccessControl {

    private final Map<CatalogSchemaTableName, String> rowFilterMappings;
    private final String perimetreCatalog;
    private final String perimetreSchema;
    private final String perimetreTable;

    public PasseportSystemAccessControl(
            Map<CatalogSchemaTableName, String> rowFilterMappings,
            String perimetreCatalog,
            String perimetreSchema,
            String perimetreTable) {
        this.rowFilterMappings = rowFilterMappings;
        this.perimetreCatalog = perimetreCatalog;
        this.perimetreSchema = perimetreSchema;
        this.perimetreTable = perimetreTable;
    }

    @Override
    public List<ViewExpression> getRowFilters(SystemSecurityContext context, CatalogSchemaTableName tableName) {
        String filterColumn = rowFilterMappings.get(tableName);
        
        if (filterColumn != null) {
            // Implémentation stricte de la note : SOUS-REQUÊTE, pas de fonction.
            String expression = String.format(
                    "%s IN (SELECT perimetre FROM %s.%s.%s WHERE upn = current_user)",
                    filterColumn, perimetreCatalog, perimetreSchema, perimetreTable
            );
            
            // Le filtre s'exécute sous l'identité de l'utilisateur courant
            return Collections.singletonList(ViewExpression.builder()
                    .identity(context.getIdentity().getUser())
                    .expression(expression)
                    .build());
        }
        
        return Collections.emptyList();
    }
}
