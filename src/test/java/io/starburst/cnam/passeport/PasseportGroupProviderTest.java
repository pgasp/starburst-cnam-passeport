package io.starburst.cnam.passeport;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.google.common.base.Ticker;
import io.airlift.units.Duration;
import io.trino.spi.TrinoException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WireMockTest
public class PasseportGroupProviderTest {

    private static final String BASE_PATH = "/s1sem/habilitations";
    private static final String APP = "SY_APP";
    private static final String USER = "user.test@example.org";

    /** Format réel de l'API, anonymisé (codes d'application, de profil et périmètres fictifs). */
    private static final String NESTED_BODY = "[{\"app\":{\"code_app\":\"SY_APP\",\"compl1\":\"%\"},\"listepa\":["
            + "{\"code_pa\":\"APP_ADMIN\",\"perimetre\":\"TOUS\"},"
            + "{\"code_pa\":\"APP_AUDITOR\",\"perimetre\":\"TOUS\"},"
            + "{\"code_pa\":\"APP_VIEWER\"},"
            + "{\"code_pa\":\"APP_NATIONAL\",\"perimetre\":\"TOUS\"}]}]";

    private static PasseportConfig config(WireMockRuntimeInfo wm) {
        return new PasseportConfig()
                .setApiUrl(wm.getHttpBaseUrl() + BASE_PATH)
                .setCodeApplication(APP);
    }

    private static void stubUser(String user, String body) {
        stubFor(get(urlEqualTo(BASE_PATH + "/" + user + "/" + APP))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static PasseportGroupProvider provider(PasseportConfig config) {
        return new PasseportGroupProvider(new PasseportAuthService(config));
    }

    @Test
    public void testNestedRealFormat(WireMockRuntimeInfo wm) {
        stubUser(USER, NESTED_BODY);
        PasseportAuthService service = new PasseportAuthService(config(wm));

        assertEquals(Set.of("APP_ADMIN", "APP_AUDITOR", "APP_VIEWER", "APP_NATIONAL"), service.getGroups(USER));
        // "TOUS" apparaît trois fois : dédoublonné ; APP_VIEWER n'a pas de périmètre.
        assertEquals(List.of("TOUS"), service.getPerimetres(USER));
    }

    @Test
    public void testNestedSingleEntryWithNumericPerimetre(WireMockRuntimeInfo wm) {
        stubUser(USER, "[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":[{\"code_pa\":\"APP_UTIL\",\"perimetre\":\"107501\"}]}]");
        PasseportAuthService service = new PasseportAuthService(config(wm));

        assertEquals(Set.of("APP_UTIL"), service.getGroups(USER));
        assertEquals(List.of("107501"), service.getPerimetres(USER));
    }

    @Test
    public void testFlatFormatStillAccepted(WireMockRuntimeInfo wm) {
        stubUser(USER, "[{\"code_pa\": \"ROLE_A\", \"perimetre\": \"311\"}, {\"code_pa\": \"ROLE_B\", \"perimetre\": \"312\"}, {\"code_pa\": \"ROLE_A\", \"perimetre\": \"311\"}]");
        PasseportAuthService service = new PasseportAuthService(config(wm));

        assertEquals(Set.of("ROLE_A", "ROLE_B"), service.getGroups(USER));
        assertEquals(List.of("311", "312"), service.getPerimetres(USER));
    }

    @Test
    public void testMixedFormats(WireMockRuntimeInfo wm) {
        stubUser(USER, "["
                + "{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":[{\"code_pa\":\"NESTED_A\",\"perimetre\":\"011\"}]},"
                + "{\"code_pa\":\"FLAT_B\",\"perimetre\":\"717\"},"
                // un même élément qui porte les deux formes
                + "{\"code_pa\":\"BOTH_C\",\"perimetre\":\"123\",\"listepa\":[{\"code_pa\":\"NESTED_D\"}]}"
                + "]");
        PasseportAuthService service = new PasseportAuthService(config(wm));

        assertEquals(Set.of("NESTED_A", "FLAT_B", "BOTH_C", "NESTED_D"), service.getGroups(USER));
        assertEquals(List.of("011", "717", "123"), service.getPerimetres(USER));
    }

    @Test
    public void testMultiplePerimetresInOneValue(WireMockRuntimeInfo wm) {
        stubUser(USER, "[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":["
                + "{\"code_pa\":\"APP_A\",\"perimetre\":\"011,717\"},"
                + "{\"code_pa\":\"APP_B\",\"perimetre\":\" 717 , ,011,  222,\"}]}]");
        PasseportAuthService service = new PasseportAuthService(config(wm));

        // découpé sur la virgule, trim, valeurs vides ignorées, doublons éliminés, ordre d'apparition conservé
        assertEquals(List.of("011", "717", "222"), service.getPerimetres(USER));
    }

    @Test
    public void testSingleValueTousIsKeptAsIs(WireMockRuntimeInfo wm) {
        stubUser(USER, "[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":[{\"code_pa\":\"APP_A\",\"perimetre\":\"TOUS\"}]}]");
        assertEquals(List.of("TOUS"), new PasseportAuthService(config(wm)).getPerimetres(USER));
    }

    @Test
    public void testCodePaWithoutPerimetre(WireMockRuntimeInfo wm) {
        stubUser(USER, "[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":[{\"code_pa\":\"APP_VIEWER\"}]}]");
        PasseportAuthService service = new PasseportAuthService(config(wm));

        assertEquals(Set.of("APP_VIEWER"), service.getGroups(USER));
        assertTrue(service.getPerimetres(USER).isEmpty());
    }

    @Test
    public void testEmptyArray(WireMockRuntimeInfo wm) {
        stubUser(USER, "[]");
        PasseportAuthService service = new PasseportAuthService(config(wm));

        assertTrue(service.getGroups(USER).isEmpty());
        assertTrue(service.getPerimetres(USER).isEmpty());
    }

    @Test
    public void testApiReturns500FailClosed(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo(BASE_PATH + "/" + USER + "/" + APP)).willReturn(aResponse().withStatus(500)));
        PasseportGroupProvider provider = provider(config(wm));

        assertThrows(TrinoException.class, () -> provider.getGroups(USER));
    }

    @Test
    public void testApiTimeoutFailClosed(WireMockRuntimeInfo wm) {
        // Le délai de requête du plugin est de 15 s.
        stubFor(get(urlEqualTo(BASE_PATH + "/" + USER + "/" + APP))
                .willReturn(aResponse().withStatus(200).withFixedDelay(16000)));
        PasseportGroupProvider provider = provider(config(wm));

        assertThrows(TrinoException.class, () -> provider.getGroups(USER));
    }

    @Test
    public void testUnreachableApiFailClosed() {
        PasseportConfig config = new PasseportConfig().setApiUrl("http://127.0.0.1:1/s1sem/habilitations").setCodeApplication(APP);
        assertThrows(TrinoException.class, () -> provider(config).getGroups(USER));
    }

    @Test
    public void testMalformedResponseFailClosed(WireMockRuntimeInfo wm) {
        stubUser(USER, "{\"error\":\"unexpected\"}");
        PasseportGroupProvider provider = provider(config(wm));

        assertThrows(TrinoException.class, () -> provider.getGroups(USER));
    }

    @Test
    public void testFailureIsNotCached(WireMockRuntimeInfo wm) {
        String path = BASE_PATH + "/" + USER + "/" + APP;
        stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(500)));
        PasseportGroupProvider provider = provider(config(wm));
        assertThrows(TrinoException.class, () -> provider.getGroups(USER));

        // L'API revient : la requête suivante réinterroge l'API (l'échec n'a pas été mémorisé).
        stubUser(USER, NESTED_BODY);
        assertEquals(4, provider.getGroups(USER).size());
        verify(2, getRequestedFor(urlEqualTo(path)));
    }

    @Test
    public void testTrailingSlashInApiUrlIsNormalized(WireMockRuntimeInfo wm) {
        stubUser(USER, NESTED_BODY);
        PasseportConfig config = new PasseportConfig()
                .setApiUrl(wm.getHttpBaseUrl() + BASE_PATH + "//")
                .setCodeApplication(APP);

        assertEquals(4, provider(config).getGroups(USER).size());
    }

    @Test
    public void testUpnWithSpecialCharactersIsEncodedInThePath(WireMockRuntimeInfo wm) {
        // upn -> segment de chemin attendu (RFC 3986) ; le "@" d'un UPN reste tel quel.
        String[][] cases = {
                {"user.test+tag@example.org", "user.test%2Btag@example.org"},
                {"o'brien@example.org", "o%27brien@example.org"},
                {"a b@example.org", "a%20b@example.org"},
                {"a/b?c#d@example.org", "a%2Fb%3Fc%23d@example.org"},
                {"..", "%2E%2E"},
                {"émile@example.org", "%C3%A9mile@example.org"},
                {"x;y=z@example.org", "x%3By%3Dz@example.org"},
        };
        for (String[] c : cases) {
            String path = BASE_PATH + "/" + c[1] + "/" + APP;
            stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(200).withBody(NESTED_BODY)));

            assertEquals(4, provider(config(wm)).getGroups(c[0]).size(), "UPN " + c[0]);
            verify(1, getRequestedFor(urlEqualTo(path)));
        }
    }

    @Test
    public void testEmptyOrNullUser(WireMockRuntimeInfo wm) {
        PasseportGroupProvider provider = provider(config(wm));
        assertTrue(provider.getGroups(null).isEmpty());
        assertTrue(provider.getGroups("").isEmpty());
    }

    // ---- cache ----

    private static final class FakeTicker extends Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(long amount, TimeUnit unit) {
            nanos.addAndGet(unit.toNanos(amount));
        }
    }

    @Test
    public void testCacheTtl(WireMockRuntimeInfo wm) {
        String path = BASE_PATH + "/" + USER + "/" + APP;
        stubUser(USER, NESTED_BODY);
        FakeTicker ticker = new FakeTicker();
        PasseportAuthService service = new PasseportAuthService(
                config(wm).setCacheTtl(new Duration(30, TimeUnit.SECONDS)), ticker);

        service.getGroups(USER);
        service.getPerimetres(USER);
        ticker.advance(29, TimeUnit.SECONDS);
        service.getGroups(USER);
        verify(1, getRequestedFor(urlEqualTo(path)));   // toujours en cache avant le TTL

        ticker.advance(2, TimeUnit.SECONDS);
        service.getGroups(USER);
        verify(2, getRequestedFor(urlEqualTo(path)));   // expiré : nouvel appel API
    }

    @Test
    public void testDefaultCacheTtlIsFifteenMinutes(WireMockRuntimeInfo wm) {
        assertEquals(new Duration(15, TimeUnit.MINUTES), new PasseportConfig().getCacheTtl());
        assertEquals(10_000, new PasseportConfig().getCacheMaxSize());

        String path = BASE_PATH + "/" + USER + "/" + APP;
        stubUser(USER, NESTED_BODY);
        FakeTicker ticker = new FakeTicker();
        PasseportAuthService service = new PasseportAuthService(config(wm), ticker);

        service.getGroups(USER);
        ticker.advance(14, TimeUnit.MINUTES);
        service.getGroups(USER);
        verify(1, getRequestedFor(urlEqualTo(path)));
        ticker.advance(2, TimeUnit.MINUTES);
        service.getGroups(USER);
        verify(2, getRequestedFor(urlEqualTo(path)));
    }

    @Test
    public void testFlushInvalidatesTheCache(WireMockRuntimeInfo wm) {
        String path = BASE_PATH + "/" + USER + "/" + APP;
        stubUser(USER, NESTED_BODY);
        PasseportAuthService service = new PasseportAuthService(config(wm));

        service.getGroups(USER);
        service.flush(USER);
        service.getGroups(USER);
        verify(2, getRequestedFor(urlEqualTo(path)));
    }
}
