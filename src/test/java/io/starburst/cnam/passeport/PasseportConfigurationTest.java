package io.starburst.cnam.passeport;

import io.trino.spi.TrinoException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PasseportConfigurationTest {

    private static final String URL = "https://passeport.example.org/api/habilitations";

    @Test
    public void apiUrlAndCodeApplicationHaveNoDefault() {
        PasseportConfig config = new PasseportConfig();
        assertEquals(null, config.getApiUrl());
        assertEquals(null, config.getCodeApplication());
    }

    @Test
    public void missingApiUrlFailsAtStartup() {
        TrinoException e = assertThrows(TrinoException.class,
                () -> new PasseportAuthService(new PasseportConfig().setCodeApplication("SY_APP")));
        assertTrue(e.getMessage().contains("passeport.api-url"), e.getMessage());
    }

    @Test
    public void blankApiUrlFailsAtStartup() {
        TrinoException e = assertThrows(TrinoException.class,
                () -> new PasseportAuthService(new PasseportConfig().setApiUrl("  ").setCodeApplication("SY_APP")));
        assertTrue(e.getMessage().contains("passeport.api-url"), e.getMessage());
    }

    @Test
    public void missingCodeApplicationFailsAtStartup() {
        TrinoException e = assertThrows(TrinoException.class,
                () -> new PasseportAuthService(new PasseportConfig().setApiUrl(URL)));
        assertTrue(e.getMessage().contains("passeport.code-application"), e.getMessage());

        e = assertThrows(TrinoException.class,
                () -> new PasseportAuthService(new PasseportConfig().setApiUrl(URL).setCodeApplication("")));
        assertTrue(e.getMessage().contains("passeport.code-application"), e.getMessage());
    }

    @Test
    public void invalidApiUrlFailsAtStartup() {
        for (String bad : new String[] {"passeport.example.org/habilitations", "ftp://passeport.example.org/x", "https://passeport.example.org/x?a=1", "https://", "not a url"}) {
            TrinoException e = assertThrows(TrinoException.class,
                    () -> new PasseportAuthService(new PasseportConfig().setApiUrl(bad).setCodeApplication("SY_APP")), bad);
            assertTrue(e.getMessage().contains("passeport.api-url"), e.getMessage());
        }
    }

    @Test
    public void trailingSlashesAreRemovedFromApiUrl() {
        assertEquals(URL, PasseportAuthService.requireBaseUrl(URL + "/"));
        assertEquals(URL, PasseportAuthService.requireBaseUrl(" " + URL + "/// "));
    }

    @Test
    public void builtUrlIsBaseThenUpnThenApplication() {
        assertEquals(URL + "/user.test@example.org/SY_APP",
                PasseportAuthService.buildUri(URL, "user.test@example.org", "SY_APP").toString());
    }

    @Test
    public void pathSegmentEncoding() {
        assertEquals("user.test@example.org", PasseportAuthService.encodePathSegment("user.test@example.org"));
        assertEquals("a%2Fb", PasseportAuthService.encodePathSegment("a/b"));
        assertEquals("%2E", PasseportAuthService.encodePathSegment("."));
        assertEquals("%2E%2E", PasseportAuthService.encodePathSegment(".."));
        assertEquals("a..b", PasseportAuthService.encodePathSegment("a..b"));
        assertEquals("%25", PasseportAuthService.encodePathSegment("%"));
        assertEquals("%F0%9F%98%80", PasseportAuthService.encodePathSegment("😀"));
    }

    // ---- via la factory, comme le fait SEP au chargement de group-provider.properties ----

    @Test
    public void factoryStartsWithRequiredPropertiesAndCacheOptions() {
        assertNotNull(new PasseportGroupProviderFactory().create(Map.of(
                "passeport.api-url", URL,
                "passeport.code-application", "SY_APP",
                "passeport.cache-ttl", "30s",
                "passeport.cache-max-size", "500")));
    }

    @Test
    public void factoryFailsWithAClearMessageWhenApiUrlIsMissing() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> new PasseportGroupProviderFactory().create(Map.of("passeport.code-application", "SY_APP")));
        assertTrue(e.getMessage().contains("passeport.api-url"), e.getMessage());
    }

    @Test
    public void factoryFailsWithAClearMessageWhenCodeApplicationIsMissing() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> new PasseportGroupProviderFactory().create(Map.of("passeport.api-url", URL)));
        assertTrue(e.getMessage().contains("passeport.code-application"), e.getMessage());
    }

    @Test
    public void factoryFailsWhenNoPropertyIsGiven() {
        assertThrows(RuntimeException.class, () -> new PasseportGroupProviderFactory().create(Map.of()));
    }

    @Test
    public void factoryRejectsInvalidCacheOptions() {
        for (Map.Entry<String, String> bad : Map.of(
                "passeport.cache-ttl", "abc",
                "passeport.cache-max-size", "0").entrySet()) {
            assertThrows(RuntimeException.class, () -> new PasseportGroupProviderFactory().create(Map.of(
                    "passeport.api-url", URL,
                    "passeport.code-application", "SY_APP",
                    bad.getKey(), bad.getValue())), bad.toString());
        }
        // sous le minimum de 1s
        assertThrows(RuntimeException.class, () -> new PasseportGroupProviderFactory().create(Map.of(
                "passeport.api-url", URL,
                "passeport.code-application", "SY_APP",
                "passeport.cache-ttl", "0s")));
        TrinoException e = assertThrows(TrinoException.class, () -> new PasseportAuthService(
                new PasseportConfig().setApiUrl(URL).setCodeApplication("SY_APP").setCacheTtl(new io.airlift.units.Duration(999, java.util.concurrent.TimeUnit.MILLISECONDS))));
        assertTrue(e.getMessage().contains("passeport.cache-ttl"), e.getMessage());
    }

    @Test
    public void factoryRejectsUnknownProperties() {
        assertThrows(RuntimeException.class, () -> new PasseportGroupProviderFactory().create(Map.of(
                "passeport.api-url", URL,
                "passeport.code-application", "SY_APP",
                "passeport.unknown", "x")));
    }
}
