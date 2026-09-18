package io.starburst.cnam.passeport;

import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemSecurityContext;
import io.trino.spi.security.ViewExpression;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.trino.spi.StandardErrorCode;
import java.util.Locale;
import io.trino.spi.TrinoException;
import io.trino.spi.security.TrinoPrincipal;
import io.trino.spi.security.Privilege;
import io.trino.spi.type.Type;
import io.trino.spi.function.SchemaFunctionName;
import io.trino.spi.connector.CatalogSchemaRoutineName;
import io.trino.spi.connector.EntityKindAndName;
import java.security.Principal;
import io.trino.spi.connector.SchemaTableName;
import java.util.stream.Collectors;
import io.trino.spi.connector.EntityPrivilege;
import io.trino.spi.security.Identity;
import java.util.Collection;
import io.trino.spi.QueryId;
import io.trino.spi.connector.CatalogSchemaName;
import io.trino.spi.connector.ColumnSchema;
import java.util.Set;
import io.trino.spi.eventlistener.EventListener;

public class PasseportSystemAccessControl implements SystemAccessControl {

    private final Map<CatalogSchemaTableName, String> rowFilterMappings;

    public PasseportSystemAccessControl(
            Map<CatalogSchemaTableName, String> rowFilterMappings) {
        this.rowFilterMappings = rowFilterMappings;
    }

    @Override
    public List<ViewExpression> getRowFilters(SystemSecurityContext context, CatalogSchemaTableName tableName) {
        String filterColumn = rowFilterMappings.get(tableName);
        
        if (filterColumn != null) {
            String user = context.getIdentity().getUser();
            List<String> perimetres = PasseportPerimetreCache.getInstance().getPerimetre(user);
            
            String expression;
            if (perimetres == null || perimetres.isEmpty()) {
                // Fail-closed : aucun périmètre en cache, on bloque l'accès
                expression = "FALSE";
            } else {
                // Construction d'une clause IN performante : filterColumn IN ('A', 'B')
                String inList = perimetres.stream()
                        .map(p -> "'" + p.replace("'", "''") + "'")
                        .collect(Collectors.joining(", "));
                
                expression = String.format("%s IN (%s)", filterColumn, inList);
            }
            
            // Le filtre s'exécute sous l'identité du compte de service (Definer rights) 
            // pour éviter le blocage BIAC lié à la perte de contexte des rôles.
            return Collections.singletonList(ViewExpression.builder()
                    .identity("starburst_service")
                    .expression(expression)
                    .build());
        }
        return Collections.emptyList();
    }

    @Override
    public void checkCanImpersonateUser(Identity identity, String userName) {
        // Allow by default
    }
    @Override
    public void checkCanSetUser(Optional<Principal> principal, String userName) {
        // Allow by default
    }
    @Override
    public void checkCanExecuteQuery(Identity identity, QueryId queryId) {
        // Allow by default
    }
    @Override
    public void checkCanViewQueryOwnedBy(Identity identity, Identity queryOwner) {
        // Allow by default
    }
    @Override
    public Collection<Identity> filterViewQueryOwnedBy(Identity identity, Collection<Identity> queryOwners) {
        return queryOwners;
    }
    @Override
    public void checkCanKillQueryOwnedBy(Identity identity, Identity queryOwner) {
        // Allow by default
    }
    @Override
    public void checkCanReadSystemInformation(Identity identity) {
        // Allow by default
    }
    @Override
    public void checkCanWriteSystemInformation(Identity identity) {
        // Allow by default
    }
    @Override
    public void checkCanSetSystemSessionProperty(Identity identity, QueryId queryId, String propertyName) {
        // Allow by default
    }
    @Override
    public boolean canAccessCatalog(SystemSecurityContext context, String catalogName) {
        return true;
    }
    @Override
    public void checkCanCreateCatalog(SystemSecurityContext context, String catalog) {
        // Allow by default
    }
    @Override
    public void checkCanDropCatalog(SystemSecurityContext context, String catalog) {
        // Allow by default
    }
    @Override
    public Set<String> filterCatalogs(SystemSecurityContext context, Set<String> catalogs) {
        return catalogs;
    }
    @Override
    public void checkCanCreateSchema(SystemSecurityContext context, CatalogSchemaName schema, Map<String, Object> properties) {
        // Allow by default
    }
    @Override
    public void checkCanDropSchema(SystemSecurityContext context, CatalogSchemaName schema) {
        // Allow by default
    }
    @Override
    public void checkCanRenameSchema(SystemSecurityContext context, CatalogSchemaName schema, String newSchemaName) {
        // Allow by default
    }
    @Override
    public void checkCanSetSchemaAuthorization(SystemSecurityContext context, CatalogSchemaName schema, TrinoPrincipal principal) {
        // Allow by default
    }
    @Override
    public void checkCanShowSchemas(SystemSecurityContext context, String catalogName) {
        // Allow by default
    }
    @Override
    public Set<String> filterSchemas(SystemSecurityContext context, String catalogName, Set<String> schemaNames) {
        return schemaNames;
    }
    @Override
    public void checkCanShowCreateSchema(SystemSecurityContext context, CatalogSchemaName schemaName) {
        // Allow by default
    }
    @Override
    public void checkCanShowCreateTable(SystemSecurityContext context, CatalogSchemaTableName table) {
        // Allow by default
    }
    @Override
    public void checkCanCreateTable(SystemSecurityContext context, CatalogSchemaTableName table, Map<String, Object> properties) {
        // Allow by default
    }
    @Override
    public void checkCanDropTable(SystemSecurityContext context, CatalogSchemaTableName table) {
        // Allow by default
    }
    @Override
    public void checkCanRenameTable(SystemSecurityContext context, CatalogSchemaTableName table, CatalogSchemaTableName newTable) {
        // Allow by default
    }
    @Override
    public void checkCanSetTableProperties(SystemSecurityContext context, CatalogSchemaTableName table, Map<String, Optional<Object>> properties) {
        // Allow by default
    }
    @Override
    public void checkCanSetTableComment(SystemSecurityContext context, CatalogSchemaTableName table) {
        // Allow by default
    }
    @Override
    public void checkCanSetViewComment(SystemSecurityContext context, CatalogSchemaTableName view) {
        // Allow by default
    }
    @Override
    public void checkCanSetColumnComment(SystemSecurityContext context, CatalogSchemaTableName table) {
        // Allow by default
    }
    @Override
    public void checkCanShowTables(SystemSecurityContext context, CatalogSchemaName schema) {
        // Allow by default
    }
    @Override
    public Set<SchemaTableName> filterTables(SystemSecurityContext context, String catalogName, Set<SchemaTableName> tableNames) {
        return tableNames;
    }
    @Override
    public void checkCanShowColumns(SystemSecurityContext context, CatalogSchemaTableName table) {
        // Allow by default
    }
    @Override
    public Set<String> filterColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns) {
        return columns;
    }
    @Override
    public void checkCanAddColumn(SystemSecurityContext context, CatalogSchemaTableName table) {
        // Allow by default
    }
    @Override
    public void checkCanAlterColumn(SystemSecurityContext context, CatalogSchemaTableName table) {
        // Allow by default
    }
    @Override
    public void checkCanDropColumn(SystemSecurityContext context, CatalogSchemaTableName table) {
        // Allow by default
    }
    @Override
    public void checkCanSetTableAuthorization(SystemSecurityContext context, CatalogSchemaTableName table, TrinoPrincipal principal) {
        // Allow by default
    }
    @Override
    public void checkCanRenameColumn(SystemSecurityContext context, CatalogSchemaTableName table) {
        // Allow by default
    }
    @Override
    public void checkCanSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Optional<String> branch, Set<String> columns) {
        // Allow by default
    }
    @Override
    public void checkCanSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns) {
        // Allow by default
    }
    @Override
    public void checkCanInsertIntoTable(SystemSecurityContext context, CatalogSchemaTableName table, Optional<String> branch) {
        // Allow by default
    }
    @Override
    public void checkCanInsertIntoTable(SystemSecurityContext context, CatalogSchemaTableName table) {
        // Allow by default
    }
    @Override
    public void checkCanDeleteFromTable(SystemSecurityContext context, CatalogSchemaTableName table, Optional<String> branch) {
        // Allow by default
    }
    @Override
    public void checkCanDeleteFromTable(SystemSecurityContext context, CatalogSchemaTableName table) {
        // Allow by default
    }
    @Override
    public void checkCanTruncateTable(SystemSecurityContext context, CatalogSchemaTableName table) {
        // Allow by default
    }
    @Override
    public void checkCanUpdateTableColumns(SystemSecurityContext securityContext, CatalogSchemaTableName table, Optional<String> branch, Set<String> updatedColumnNames) {
        // Allow by default
    }
    @Override
    public void checkCanUpdateTableColumns(SystemSecurityContext securityContext, CatalogSchemaTableName table, Set<String> updatedColumnNames) {
        // Allow by default
    }
    @Override
    public void checkCanCreateView(SystemSecurityContext context, CatalogSchemaTableName view) {
        // Allow by default
    }
    @Override
    public void checkCanRenameView(SystemSecurityContext context, CatalogSchemaTableName view, CatalogSchemaTableName newView) {
        // Allow by default
    }
    @Override
    public void checkCanSetViewAuthorization(SystemSecurityContext context, CatalogSchemaTableName view, TrinoPrincipal principal) {
        // Allow by default
    }
    @Override
    public void checkCanRefreshView(SystemSecurityContext context, CatalogSchemaTableName viewName) {
        // Allow by default
    }
    @Override
    public void checkCanSetMaterializedViewAuthorization(SystemSecurityContext context, CatalogSchemaTableName view, TrinoPrincipal principal) {
        // Allow by default
    }
    @Override
    public void checkCanDropView(SystemSecurityContext context, CatalogSchemaTableName view) {
        // Allow by default
    }
    @Override
    public void checkCanCreateViewWithSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Optional<String> branch, Set<String> columns) {
        // Allow by default
    }
    @Override
    public void checkCanCreateViewWithSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns) {
        // Allow by default
    }
    @Override
    public void checkCanCreateMaterializedView(SystemSecurityContext context, CatalogSchemaTableName materializedView, Map<String, Object> properties) {
        // Allow by default
    }
    @Override
    public void checkCanRefreshMaterializedView(SystemSecurityContext context, CatalogSchemaTableName materializedView) {
        // Allow by default
    }
    @Override
    public void checkCanSetMaterializedViewProperties(SystemSecurityContext context, CatalogSchemaTableName materializedView, Map<String, Optional<Object>> properties) {
        // Allow by default
    }
    @Override
    public void checkCanDropMaterializedView(SystemSecurityContext context, CatalogSchemaTableName materializedView) {
        // Allow by default
    }
    @Override
    public void checkCanRenameMaterializedView(SystemSecurityContext context, CatalogSchemaTableName view, CatalogSchemaTableName newView) {
        // Allow by default
    }
    @Override
    public void checkCanSetCatalogSessionProperty(SystemSecurityContext context, String catalogName, String propertyName) {
        // Allow by default
    }
    @Override
    public void checkCanGrantSchemaPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaName schema, TrinoPrincipal grantee, boolean grantOption) {
        // Allow by default
    }
    @Override
    public void checkCanDenySchemaPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaName schema, TrinoPrincipal grantee) {
        // Allow by default
    }
    @Override
    public void checkCanRevokeSchemaPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaName schema, TrinoPrincipal revokee, boolean grantOption) {
        // Allow by default
    }
    @Override
    public void checkCanGrantTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, TrinoPrincipal grantee, boolean grantOption) {
        // Allow by default
    }
    @Override
    public void checkCanDenyTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, TrinoPrincipal grantee) {
        // Allow by default
    }
    @Override
    public void checkCanRevokeTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, TrinoPrincipal revokee, boolean grantOption) {
        // Allow by default
    }
    @Override
    public void checkCanGrantTableBranchPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, String branchName, TrinoPrincipal grantee, boolean grantOption) {
        // Allow by default
    }
    @Override
    public void checkCanDenyTableBranchPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, String branchName, TrinoPrincipal grantee) {
        // Allow by default
    }
    @Override
    public void checkCanRevokeTableBranchPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, String branchName, TrinoPrincipal revokee, boolean grantOption) {
        // Allow by default
    }
    @Override
    public void checkCanGrantEntityPrivilege(SystemSecurityContext context, EntityPrivilege privilege, EntityKindAndName entity, TrinoPrincipal grantee, boolean grantOption) {
        // Allow by default
    }
    @Override
    public void checkCanDenyEntityPrivilege(SystemSecurityContext context, EntityPrivilege privilege, EntityKindAndName entity, TrinoPrincipal grantee) {
        // Allow by default
    }
    @Override
    public void checkCanRevokeEntityPrivilege(SystemSecurityContext context, EntityPrivilege privilege, EntityKindAndName entity, TrinoPrincipal revokee, boolean grantOption) {
        // Allow by default
    }
    @Override
    public void checkCanShowRoles(SystemSecurityContext context) {
        // Allow by default
    }
    @Override
    public void checkCanCreateRole(SystemSecurityContext context, String role, Optional<TrinoPrincipal> grantor) {
        // Allow by default
    }
    @Override
    public void checkCanDropRole(SystemSecurityContext context, String role) {
        // Allow by default
    }
    @Override
    public void checkCanGrantRoles(SystemSecurityContext context, Set<String> roles, Set<TrinoPrincipal> grantees, boolean adminOption, Optional<TrinoPrincipal> grantor) {
        // Allow by default
    }
    @Override
    public void checkCanRevokeRoles(SystemSecurityContext context, Set<String> roles, Set<TrinoPrincipal> grantees, boolean adminOption, Optional<TrinoPrincipal> grantor) {
        // Allow by default
    }
    @Override
    public void checkCanShowCurrentRoles(SystemSecurityContext context) {
        // Allow by default
    }
    @Override
    public void checkCanShowRoleGrants(SystemSecurityContext context) {
        // Allow by default
    }
    @Override
    public void checkCanExecuteProcedure(SystemSecurityContext systemSecurityContext, CatalogSchemaRoutineName procedure) {
        // Allow by default
    }
    @Override
    public boolean canExecuteFunction(SystemSecurityContext systemSecurityContext, CatalogSchemaRoutineName functionName) {
        return true;
    }
    @Override
    public boolean canCreateViewWithExecuteFunction(SystemSecurityContext systemSecurityContext, CatalogSchemaRoutineName functionName) {
        return true;
    }
    @Override
    public void checkCanExecuteTableProcedure(SystemSecurityContext systemSecurityContext, CatalogSchemaTableName table, String procedure) {
        // Allow by default
    }
    @Override
    public void checkCanShowFunctions(SystemSecurityContext context, CatalogSchemaName schema) {
        // Allow by default
    }
    @Override
    public Set<SchemaFunctionName> filterFunctions(SystemSecurityContext context, String catalogName, Set<SchemaFunctionName> functionNames) {
        return functionNames;
    }
    @Override
    public void checkCanCreateFunction(SystemSecurityContext systemSecurityContext, CatalogSchemaRoutineName functionName) {
        // Allow by default
    }
    @Override
    public void checkCanDropFunction(SystemSecurityContext systemSecurityContext, CatalogSchemaRoutineName functionName) {
        // Allow by default
    }
    @Override
    public void checkCanShowCreateFunction(SystemSecurityContext systemSecurityContext, CatalogSchemaRoutineName functionName) {
        // Allow by default
    }
    @Override
    public void checkCanShowBranches(SystemSecurityContext systemSecurityContext, CatalogSchemaTableName tableName) {
        // Allow by default
    }
    @Override
    public void checkCanCreateBranch(SystemSecurityContext systemSecurityContext, CatalogSchemaTableName tableName, String branchName) {
        // Allow by default
    }
    @Override
    public void checkCanDropBranch(SystemSecurityContext systemSecurityContext, CatalogSchemaTableName tableName, String branchName) {
        // Allow by default
    }
    @Override
    public void checkCanFastForwardBranch(SystemSecurityContext systemSecurityContext, CatalogSchemaTableName tableName, String sourceBranchName, String targetBranchName) {
        // Allow by default
    }
    @Override
    public Optional<ViewExpression> getColumnMask(SystemSecurityContext context, CatalogSchemaTableName tableName, String columnName, Type type) {
        return Optional.empty();
    }
    @Override
    public void checkCanSetEntityAuthorization(SystemSecurityContext context, EntityKindAndName entityKindAndName, TrinoPrincipal principal) {
        // Allow by default
    }
    @Override
    public Iterable<EventListener> getEventListeners() {
        return java.util.Collections.emptySet();
    }
}
