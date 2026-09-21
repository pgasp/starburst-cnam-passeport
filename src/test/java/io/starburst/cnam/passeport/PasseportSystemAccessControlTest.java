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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import io.trino.spi.connector.CatalogSchemaTableName;

public class PasseportSystemAccessControlTest {

    private PasseportSystemAccessControl accessControl;
    private PasseportAuthService authService;

    @BeforeEach
    public void setup() {
        authService = mock(PasseportAuthService.class);
        Map<CatalogSchemaTableName, String> rowFilterMappings = new HashMap<>();
        rowFilterMappings.put(null, "region_id");
        
        PasseportConfig config = new PasseportConfig().setRowFilterMappings("");
        
        accessControl = new PasseportSystemAccessControl(config, authService);
        try {
            java.lang.reflect.Field field = PasseportSystemAccessControl.class.getDeclaredField("rowFilterMappings");
            field.setAccessible(true);
            field.set(accessControl, rowFilterMappings);
        } catch (Exception e) {}
    }

    @Test
    public void testGetRowFiltersWithServiceAccount() {
        Map<CatalogSchemaTableName, String> rowFilterMappings = new HashMap<>();
        rowFilterMappings.put(null, "region_id");
        
        PasseportConfig config = new PasseportConfig().setServiceAccount("starburst_service");
        PasseportSystemAccessControl serviceAccountAccessControl = new PasseportSystemAccessControl(config, authService);
        try {
            java.lang.reflect.Field field = PasseportSystemAccessControl.class.getDeclaredField("rowFilterMappings");
            field.setAccessible(true);
            field.set(serviceAccountAccessControl, rowFilterMappings);
        } catch (Exception e) {}
        
        Identity identity = Identity.ofUser("alice.smith");
        SystemSecurityContext context = new SystemSecurityContext(identity, new QueryId("q1"), Instant.now());
        
        when(authService.getPerimetres("alice.smith")).thenReturn(Arrays.asList("IDF"));
        
        List<ViewExpression> rowFilters = serviceAccountAccessControl.getRowFilters(context, null);
        ViewExpression viewExpression = rowFilters.get(0);
        
        Optional<String> securityIdentity = viewExpression.getSecurityIdentity();
        assertTrue(securityIdentity.isPresent());
        assertEquals("starburst_service", securityIdentity.get());
    }

    @Test
    public void testGetRowFiltersWithMappedTableAndPopulatedCache() {
        Identity identity = Identity.ofUser("alice.smith");
        SystemSecurityContext context = new SystemSecurityContext(identity, new QueryId("q1"), Instant.now());
        
        when(authService.getPerimetres("alice.smith")).thenReturn(Arrays.asList("IDF", "PACA"));
        
        List<ViewExpression> rowFilters = accessControl.getRowFilters(context, null);
        
        assertEquals(1, rowFilters.size());
        ViewExpression viewExpression = rowFilters.get(0);
        
        assertEquals("region_id IN ('IDF', 'PACA')", viewExpression.getExpression());
        assertTrue(viewExpression.getSecurityIdentity().isEmpty());
    }

    @Test
    public void testGetRowFiltersWithMappedTableAndEmptyCache() {
        Identity identity = Identity.ofUser("charlie.brown");
        SystemSecurityContext context = new SystemSecurityContext(identity, new QueryId("q3"), Instant.now());
        
        when(authService.getPerimetres("charlie.brown")).thenReturn(Collections.emptyList());
        
        List<ViewExpression> rowFilters = accessControl.getRowFilters(context, null);
        
        assertEquals(1, rowFilters.size());
        assertEquals("FALSE", rowFilters.get(0).getExpression());
    }
}
