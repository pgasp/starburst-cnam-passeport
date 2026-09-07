package io.starburst.cnam.passeport;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest
public class PasseportGroupProviderTest {

    private PasseportPerimetreCache cache;

    @BeforeEach
    public void setup() {
        cache = PasseportPerimetreCache.getInstance();
        cache.flushAll();
    }

    @Test
    public void testSuccessfulGroupResolution(WireMockRuntimeInfo wmRuntimeInfo) {
        String user = "jean.dupont";
        String app = "MATIS_PROD";
        
        // Mock Passeport API Response
        stubFor(get(urlEqualTo("/s1sem/habilitations/" + user + "/" + app))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"code_pa\": \"ROLE_A\", \"perimetre\": \"311\"}, {\"code_pa\": \"ROLE_B\", \"perimetre\": \"312\"}, {\"code_pa\": \"ROLE_A\", \"perimetre\": \"311\"}]"))); // Also testing deduplication

        PasseportGroupProvider provider = new PasseportGroupProvider(wmRuntimeInfo.getHttpBaseUrl() + "/s1sem/habilitations", app, null, null);

        Set<String> groups = provider.getGroups(user);

        // Verify Groups
        assertEquals(2, groups.size());
        assertTrue(groups.contains("ROLE_A"));
        assertTrue(groups.contains("ROLE_B"));

        // Verify Perimetre Cache Update (and deduplication)
        assertEquals(2, cache.getPerimetre(user).size());
        assertTrue(cache.getPerimetre(user).contains("311"));
        assertTrue(cache.getPerimetre(user).contains("312"));
    }

    @Test
    public void testApiReturns500FailClosed(WireMockRuntimeInfo wmRuntimeInfo) {
        String user = "jean.dupont";
        String app = "MATIS_PROD";
        
        stubFor(get(urlEqualTo("/s1sem/habilitations/" + user + "/" + app))
                .willReturn(aResponse().withStatus(500)));

        PasseportGroupProvider provider = new PasseportGroupProvider(wmRuntimeInfo.getHttpBaseUrl() + "/s1sem/habilitations", app, null, null);

        Set<String> groups = provider.getGroups(user);

        // Must fail closed cleanly
        assertTrue(groups.isEmpty());
        assertTrue(cache.getPerimetre(user).isEmpty());
    }

    @Test
    public void testApiTimeoutFailClosed(WireMockRuntimeInfo wmRuntimeInfo) {
        String user = "jean.dupont";
        String app = "MATIS_PROD";
        
        stubFor(get(urlEqualTo("/s1sem/habilitations/" + user + "/" + app))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(16000))); // Simulate a timeout > 15s

        PasseportGroupProvider provider = new PasseportGroupProvider(wmRuntimeInfo.getHttpBaseUrl() + "/s1sem/habilitations", app, null, null);

        Set<String> groups = provider.getGroups(user);

        // Must fail closed cleanly due to timeout exception handling
        assertTrue(groups.isEmpty());
    }
    
    @Test
    public void testMalformedJsonFailClosed(WireMockRuntimeInfo wmRuntimeInfo) {
        String user = "jean.dupont";
        String app = "MATIS_PROD";
        
        stubFor(get(urlEqualTo("/s1sem/habilitations/" + user + "/" + app))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("This is not JSON")));

        PasseportGroupProvider provider = new PasseportGroupProvider(wmRuntimeInfo.getHttpBaseUrl() + "/s1sem/habilitations", app, null, null);

        Set<String> groups = provider.getGroups(user);

        assertTrue(groups.isEmpty());
    }
    
    @Test
    public void testEmptyOrNullUser() {
        PasseportGroupProvider provider = new PasseportGroupProvider("http://localhost", "MATIS", null, null);
        assertTrue(provider.getGroups(null).isEmpty());
        assertTrue(provider.getGroups("").isEmpty());
    }
}
