package io.starburst.cnam.passeport;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.trino.spi.TrinoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@WireMockTest
public class PasseportGroupProviderTest {

    @Test
    public void testSuccessfulGroupResolution(WireMockRuntimeInfo wmRuntimeInfo) {
        String user = "jean.dupont";
        String app = "MATIS_PROD";
        
        stubFor(get(urlEqualTo("/s1sem/habilitations/" + user + "/" + app))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"code_pa\": \"ROLE_A\", \"perimetre\": \"311\"}, {\"code_pa\": \"ROLE_B\", \"perimetre\": \"312\"}, {\"code_pa\": \"ROLE_A\", \"perimetre\": \"311\"}]")));

        PasseportConfig config = new PasseportConfig()
            .setApiUrl(wmRuntimeInfo.getHttpBaseUrl() + "/s1sem/habilitations")
            .setCodeApplication(app);

        PasseportAuthService authService = new PasseportAuthService(config);
        PasseportGroupProvider provider = new PasseportGroupProvider(authService);

        Set<String> groups = provider.getGroups(user);

        assertEquals(2, groups.size());
        assertTrue(groups.contains("ROLE_A"));
        assertTrue(groups.contains("ROLE_B"));

        assertEquals(2, authService.getPerimetres(user).size());
        assertTrue(authService.getPerimetres(user).contains("311"));
        assertTrue(authService.getPerimetres(user).contains("312"));
    }

    @Test
    public void testApiReturns500FailClosed(WireMockRuntimeInfo wmRuntimeInfo) {
        String user = "jean.dupont";
        String app = "MATIS_PROD";
        
        stubFor(get(urlEqualTo("/s1sem/habilitations/" + user + "/" + app))
                .willReturn(aResponse().withStatus(500)));

        PasseportConfig config = new PasseportConfig()
            .setApiUrl(wmRuntimeInfo.getHttpBaseUrl() + "/s1sem/habilitations")
            .setCodeApplication(app);

        PasseportAuthService authService = new PasseportAuthService(config);
        PasseportGroupProvider provider = new PasseportGroupProvider(authService);

        assertThrows(TrinoException.class, () -> provider.getGroups(user));
    }

    @Test
    public void testApiTimeoutFailClosed(WireMockRuntimeInfo wmRuntimeInfo) {
        String user = "jean.dupont";
        String app = "MATIS_PROD";
        
        stubFor(get(urlEqualTo("/s1sem/habilitations/" + user + "/" + app))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(16000)));

        PasseportConfig config = new PasseportConfig()
            .setApiUrl(wmRuntimeInfo.getHttpBaseUrl() + "/s1sem/habilitations")
            .setCodeApplication(app);

        PasseportAuthService authService = new PasseportAuthService(config);
        PasseportGroupProvider provider = new PasseportGroupProvider(authService);

        assertThrows(TrinoException.class, () -> provider.getGroups(user));
    }
    
    @Test
    public void testEmptyOrNullUser() {
        PasseportAuthService authService = new PasseportAuthService(new PasseportConfig());
        PasseportGroupProvider provider = new PasseportGroupProvider(authService);
        assertTrue(provider.getGroups(null).isEmpty());
        assertTrue(provider.getGroups("").isEmpty());
    }
}
