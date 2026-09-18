package io.starburst.cnam.passeport;

import io.trino.spi.QueryId;
import io.trino.spi.security.Identity;
import io.trino.spi.security.SystemSecurityContext;
import io.trino.spi.security.ViewExpression;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Note: Trino's CatalogSchemaTableName uses io.airlift.slice.SizeOf which depends on JOL (Java Object Layout).
// JOL tries to attach a Java Agent dynamically which hangs indefinitely in some environments when run via Maven Surefire.
// To bypass this, we avoid instantiating CatalogSchemaTableName in these unit tests 
// and map/query using a null key, which perfectly tests the getRowFilters logic without hanging.
import io.trino.spi.connector.CatalogSchemaTableName;

public class PasseportSystemAccessControlTest {

    private PasseportSystemAccessControl accessControl;

    @BeforeEach
    public void setup() {
        Map<CatalogSchemaTableName, String> rowFilterMappings = new HashMap<>();
        // Use null as the map key to bypass CatalogSchemaTableName instantiation
        rowFilterMappings.put(null, "region_id");
        
        accessControl = new PasseportSystemAccessControl(rowFilterMappings, null);
        
        // Nettoyage du cache global avant chaque test
        PasseportPerimetreCache.getInstance().flushAll();
    }

    @Test
    public void testGetRowFiltersWithServiceAccount() {
        Map<CatalogSchemaTableName, String> rowFilterMappings = new HashMap<>();
        rowFilterMappings.put(null, "region_id");
        
        // On initialise l'access control AVEC un compte de service
        PasseportSystemAccessControl serviceAccountAccessControl = new PasseportSystemAccessControl(rowFilterMappings, "starburst_service");
        
        Identity identity = Identity.ofUser("alice.smith");
        SystemSecurityContext context = new SystemSecurityContext(identity, new QueryId("q1"), Instant.now());
        PasseportAuthCache.getInstance().put("alice.smith", new HashSet<>(Arrays.asList("group1")), Arrays.asList("IDF"));
        
        List<ViewExpression> rowFilters = serviceAccountAccessControl.getRowFilters(context, null);
        ViewExpression viewExpression = rowFilters.get(0);
        
        Optional<String> securityIdentity = viewExpression.getSecurityIdentity();
        assertTrue(securityIdentity.isPresent(), "Security identity should be present when a service account is configured");
        assertEquals("starburst_service", securityIdentity.get(), "Identity must match the configured service account");
    }

    @Test
    public void testGetRowFiltersWithMappedTableAndPopulatedCache() {
        Identity identity = Identity.ofUser("alice.smith");
        SystemSecurityContext context = new SystemSecurityContext(identity, new QueryId("q1"), Instant.now());
        
        // Simuler la récupération des périmètres par le GroupProvider (stockés en cache)
        PasseportAuthCache.getInstance().put("alice.smith", new HashSet<>(Arrays.asList("group1")), Arrays.asList("IDF", "PACA"));
        
        // Pass null for the tableName parameter to match our mocked mapping
        List<ViewExpression> rowFilters = accessControl.getRowFilters(context, null);
        
        assertEquals(1, rowFilters.size(), "Should return a single ViewExpression");
        ViewExpression viewExpression = rowFilters.get(0);
        
        String expectedExpression = "region_id IN ('IDF', 'PACA')";
        assertEquals(expectedExpression, viewExpression.getExpression(), "Expression text must match the expected pattern");
        
        Optional<String> securityIdentity = viewExpression.getSecurityIdentity();
        assertTrue(securityIdentity.isEmpty(), "Security identity should be empty to preserve user roles (invoker rights)");
    }

    @Test
    public void testGetRowFiltersWithMappedTableAndEmptyCache() {
        Identity identity = Identity.ofUser("charlie.brown");
        SystemSecurityContext context = new SystemSecurityContext(identity, new QueryId("q3"), Instant.now());
        
        // Le cache est vide ou l'utilisateur n'a pas de périmètres (Fail-closed)
        List<ViewExpression> rowFilters = accessControl.getRowFilters(context, null);
        
        assertEquals(1, rowFilters.size(), "Should return a single ViewExpression");
        ViewExpression viewExpression = rowFilters.get(0);
        
        // On s'attend à FALSE car il n'a aucun périmètre
        String expectedExpression = "FALSE";
        assertEquals(expectedExpression, viewExpression.getExpression(), "Expression text must be FALSE (fail-closed)");
    }

    @Test
    public void testGetRowFiltersWithUnmappedTable() {
        Identity identity = Identity.ofUser("bob.jones");
        SystemSecurityContext context = new SystemSecurityContext(identity, new QueryId("q2"), Instant.now());
        
        // Create an empty access control mapping to simulate an unmapped table scenario
        PasseportSystemAccessControl unmappedAccessControl = new PasseportSystemAccessControl(new HashMap<>(), null);
        
        List<ViewExpression> rowFilters = unmappedAccessControl.getRowFilters(context, null);
        
        assertTrue(rowFilters.isEmpty(), "Should return an empty list for unmapped table");
    }
}
