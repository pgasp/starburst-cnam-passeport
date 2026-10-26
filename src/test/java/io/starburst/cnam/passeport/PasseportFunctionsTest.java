package io.starburst.cnam.passeport;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.airlift.slice.Slices;
import io.trino.spi.block.Block;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.security.ConnectorIdentity;
import io.trino.spi.type.VarcharType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Modèle de profils : {@code passeport_perimetre()} renvoie le périmètre du seul groupe dont le nom est le rôle actif.
 * Identifiants génériques uniquement (le dépôt est public).
 */
@WireMockTest
public class PasseportFunctionsTest {

    private static final String BASE_PATH = "/api/habilitations";
    private static final String APP = "SY_APP";
    private static final String USER = "user.test@example.org";
    private static final String OTHER_USER = "other.test@example.org";

    /** Trois profils : un national à 6 caisses, un local à 1 caisse, un sans caisse (périmètre vide). */
    private static final String BODY = "[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":["
            + "{\"code_pa\":\"APP_RESEAU\",\"perimetre\":\"101,102,103,104,105,106\"},"
            + "{\"code_pa\":\"APP_LOC\",\"perimetre\":\"101\"},"
            + "{\"code_pa\":\"APP_NAT\",\"perimetre\":\"\"}]}]";

    private static final Map<String, List<String>> GROUPS = Map.of(
            "APP_RESEAU", List.of("101", "102", "103", "104", "105", "106"),
            "APP_LOC", List.of("101"),
            "APP_NAT", List.of());

    @AfterEach
    public void restoreGlobalInstance() {
        PasseportAuthService.clearGlobalInstance();
    }

    // ---- règle pure (sans SEP) ----

    private static List<String> perimetre(Set<String> roles) {
        return PasseportFunctions.resolvePerimetre(roles, GROUPS, Optional.empty());
    }

    private static List<String> perimetreOf(String codePa, Set<String> roles) {
        return PasseportFunctions.resolvePerimetre(roles, GROUPS, Optional.of(codePa));
    }

    @Test
    public void singleMatchingRoleGivesThatGroupPerimetreOnly() {
        // APP_LOC (101) seul : pas les 6 caisses de APP_RESEAU, même si l'utilisateur appartient aux deux groupes.
        assertEquals(List.of("101"), perimetre(Set.of("APP_LOC")));
        assertEquals(List.of("101", "102", "103", "104", "105", "106"), perimetre(Set.of("APP_RESEAU")));
    }

    @Test
    public void twoActiveRolesGiveEmpty() {
        assertEquals(List.of(), perimetre(Set.of("APP_LOC", "APP_RESEAU")));
    }

    @Test
    public void noActiveRoleGivesEmpty() {
        assertEquals(List.of(), perimetre(Set.of()));
        assertEquals(List.of(), PasseportFunctions.resolvePerimetre(null, GROUPS, Optional.empty()));
    }

    @Test
    public void roleMatchingNoGroupGivesEmpty() {
        assertEquals(List.of(), perimetre(Set.of("APP_AUTRE")));
        // un groupe que l'utilisateur n'a pas, même s'il existe ailleurs
        assertEquals(List.of(), PasseportFunctions.resolvePerimetre(Set.of("APP_LOC"), Map.of("APP_RESEAU", List.of("101")), Optional.empty()));
        assertEquals(List.of(), PasseportFunctions.resolvePerimetre(Set.of("APP_LOC"), Map.of(), Optional.empty()));
    }

    @Test
    public void matchingIsCaseInsensitiveAndIgnoresSurroundingSpaces() {
        assertEquals(List.of("101"), perimetre(Set.of("app_loc")));
        assertEquals(List.of("101"), perimetre(Set.of("  App_Loc ")));
        assertEquals(List.of("101"), PasseportFunctions.resolvePerimetre(Set.of("APP_LOC"), Map.of(" app_loc ", List.of("101")), Optional.empty()));
        // pas de correspondance partielle
        assertEquals(List.of(), perimetre(Set.of("APP_LO")));
        assertEquals(List.of(), perimetre(Set.of("APP_LOC_X")));
    }

    @Test
    public void publicAndSysadminAreIgnored() {
        assertEquals(List.of("101"), perimetre(Set.of("public", "APP_LOC")));
        assertEquals(List.of("101"), perimetre(Set.of("PUBLIC", "Sysadmin", "APP_LOC")));
        assertEquals(List.of("101"), perimetre(Set.of(" sysadmin ", "APP_LOC")));
        // sans autre rôle : aucun profil
        assertEquals(List.of(), perimetre(Set.of("sysadmin")));
        assertEquals(List.of(), perimetre(Set.of("public")));
        assertEquals(List.of(), perimetre(Set.of("public", "sysadmin")));
        // deux rôles de profil malgré public : toujours vide
        assertEquals(List.of(), perimetre(Set.of("public", "APP_LOC", "APP_RESEAU")));
    }

    @Test
    public void groupWithEmptyPerimetreMeansNoCaisseNotAll() {
        assertEquals(List.of(), perimetre(Set.of("APP_NAT")));
        // contre-épreuve : le row filter contains(passeport_perimetre(), caisse) ne laisse alors passer aucune ligne
        assertFalse(perimetre(Set.of("APP_NAT")).contains("101"));
    }

    @Test
    public void ambiguousGroupNamesGiveEmpty() {
        // deux code_pa qui ne diffèrent que par la casse : on ne choisit pas
        Map<String, List<String>> ambiguous = new java.util.LinkedHashMap<>();
        ambiguous.put("APP_LOC", List.of("101"));
        ambiguous.put("app_loc", List.of("102"));
        assertEquals(List.of(), PasseportFunctions.resolvePerimetre(Set.of("APP_LOC"), ambiguous, Optional.empty()));
    }

    @Test
    public void overloadRequiresMembershipAndActiveRole() {
        // membre et rôle actif
        assertEquals(List.of("101"), perimetreOf("APP_LOC", Set.of("APP_LOC")));
        assertEquals(List.of("101"), perimetreOf("app_loc", Set.of("APP_LOC", "public")));
        // membre, mais ce rôle n'est pas l'actif : jamais le périmètre d'un autre profil
        assertEquals(List.of(), perimetreOf("APP_LOC", Set.of("APP_RESEAU")));
        // membre, mais plusieurs rôles actifs (le rôle visé en fait partie) : vide
        assertEquals(List.of(), perimetreOf("APP_LOC", Set.of("APP_LOC", "APP_RESEAU")));
        // aucun rôle actif
        assertEquals(List.of(), perimetreOf("APP_LOC", Set.of()));
        // rôle actif mais non membre du groupe
        assertEquals(List.of(), perimetreOf("APP_AUTRE", Set.of("APP_AUTRE")));
        // groupe vide : aucune caisse
        assertEquals(List.of(), perimetreOf("APP_NAT", Set.of("APP_NAT")));
    }

    // ---- bout en bout : fonctions SQL, session simulée, API simulée ----

    private static PasseportConfig config(WireMockRuntimeInfo wm) {
        return new PasseportConfig()
                .setApiUrl(wm.getHttpBaseUrl() + BASE_PATH)
                .setCodeApplication(APP);
    }

    private static void stubUser(String user, String body) {
        stubFor(get(urlEqualTo(BASE_PATH + "/" + user + "/" + APP))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static ConnectorSession session(String user, String... roles) {
        ConnectorSession session = mock(ConnectorSession.class);
        when(session.getUser()).thenReturn(user);
        if (user != null) {
            when(session.getIdentity()).thenReturn(ConnectorIdentity.forUser(user).withEnabledSystemRoles(Set.of(roles)).build());
        }
        return session;
    }

    private static List<String> values(Block block) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < block.getPositionCount(); i++) {
            out.add(VarcharType.VARCHAR.getSlice(block, i).toStringUtf8());
        }
        return out;
    }

    @Test
    public void functionsEndToEnd(WireMockRuntimeInfo wm) {
        stubUser(USER, BODY);
        new PasseportAuthService(config(wm));

        assertEquals(List.of("101"), values(PasseportFunctions.getPasseportPerimetre(session(USER, "public", "APP_LOC"))));
        assertEquals(List.of(), values(PasseportFunctions.getPasseportPerimetre(session(USER, "APP_LOC", "APP_RESEAU"))));
        assertEquals(List.of(), values(PasseportFunctions.getPasseportPerimetre(session(USER))));
        assertEquals(List.of(), values(PasseportFunctions.getPasseportPerimetre(session(USER, "sysadmin"))));
        assertEquals(List.of(), values(PasseportFunctions.getPasseportPerimetre(session(USER, "APP_AUTRE"))));

        assertEquals(List.of("101"), values(PasseportFunctions.getPasseportPerimetreOfGroup(session(USER, "APP_LOC"), Slices.utf8Slice("APP_LOC"))));
        // appartenance sans rôle actif correspondant
        assertEquals(List.of(), values(PasseportFunctions.getPasseportPerimetreOfGroup(session(USER, "APP_RESEAU"), Slices.utf8Slice("APP_LOC"))));
        assertEquals(List.of(), values(PasseportFunctions.getPasseportPerimetreOfGroup(session(USER, "APP_LOC"), null)));

        // un seul appel API pour toutes ces évaluations (cache)
        verify(1, getRequestedFor(urlEqualTo(BASE_PATH + "/" + USER + "/" + APP)));
    }

    @Test
    public void perimetreIsEmptyWithoutServiceOrUser() {
        PasseportAuthService.clearGlobalInstance();
        assertEquals(List.of(), values(PasseportFunctions.getPasseportPerimetre(session(USER, "APP_LOC"))));
        assertEquals(List.of(), values(PasseportFunctions.getPasseportPerimetre(null)));
    }

    // ---- purge du cache ----

    @Test
    public void invalidateRefreshesTheCurrentUserOnly(WireMockRuntimeInfo wm) {
        stubUser(USER, BODY);
        stubUser(OTHER_USER, BODY);
        PasseportAuthService service = new PasseportAuthService(config(wm));
        service.getPerimetresByGroup(USER);
        service.getPerimetresByGroup(OTHER_USER);
        verify(1, getRequestedFor(urlEqualTo(BASE_PATH + "/" + USER + "/" + APP)));
        verify(1, getRequestedFor(urlEqualTo(BASE_PATH + "/" + OTHER_USER + "/" + APP)));

        assertTrue(PasseportFunctions.invalidateCurrentUserCache(session(USER)));

        service.getPerimetresByGroup(USER);
        service.getPerimetresByGroup(OTHER_USER);
        verify(2, getRequestedFor(urlEqualTo(BASE_PATH + "/" + USER + "/" + APP)));          // rechargé
        verify(1, getRequestedFor(urlEqualTo(BASE_PATH + "/" + OTHER_USER + "/" + APP)));    // intact
    }

    @Test
    public void invalidatePicksUpAChangedHabilitation(WireMockRuntimeInfo wm) {
        stubUser(USER, "[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":[{\"code_pa\":\"APP_LOC\",\"perimetre\":\"101\"}]}]");
        new PasseportAuthService(config(wm));
        assertEquals(List.of("101"), values(PasseportFunctions.getPasseportPerimetre(session(USER, "APP_LOC"))));

        stubUser(USER, "[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":[{\"code_pa\":\"APP_LOC\",\"perimetre\":\"101,107\"}]}]");
        // encore l'ancienne valeur tant que le cache n'est pas purgé
        assertEquals(List.of("101"), values(PasseportFunctions.getPasseportPerimetre(session(USER, "APP_LOC"))));

        assertTrue(PasseportFunctions.invalidateCurrentUserCache(session(USER, "APP_LOC")));
        assertEquals(List.of("101", "107"), values(PasseportFunctions.getPasseportPerimetre(session(USER, "APP_LOC"))));
    }

    @Test
    public void invalidateWithoutServiceOrUserReturnsFalseAndNeverFlushesEverything(WireMockRuntimeInfo wm) {
        // nœud sans group provider (worker) : rien n'est invalidé, la réponse le dit
        PasseportAuthService.clearGlobalInstance();
        assertFalse(PasseportFunctions.invalidateCurrentUserCache(session(USER)));
        assertFalse(PasseportFunctions.invalidateCurrentUserCache(null));

        // utilisateur inexploitable : false, et le cache des autres reste en place (pas de purge globale par cette voie)
        stubUser(OTHER_USER, BODY);
        PasseportAuthService service = new PasseportAuthService(config(wm));
        service.getPerimetresByGroup(OTHER_USER);
        assertFalse(PasseportFunctions.invalidateCurrentUserCache(session(null)));
        assertFalse(PasseportFunctions.invalidateCurrentUserCache(session("  ")));
        service.getPerimetresByGroup(OTHER_USER);
        verify(1, getRequestedFor(urlEqualTo(BASE_PATH + "/" + OTHER_USER + "/" + APP)));
    }

    // ---- modèle de données : périmètre par groupe ----

    @Test
    public void parserKeepsPerimetreByGroupInOrderAndMergesRepeatedCodePa() {
        PasseportAuthService.AuthData data = PasseportResponseParser.parse(
                "[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":["
                        + "{\"code_pa\":\"APP_LOC\",\"perimetre\":\"101, 102\"},"
                        + "{\"code_pa\":\"APP_RESEAU\",\"perimetre\":\"103\"},"
                        + "{\"code_pa\":\"APP_LOC\",\"perimetre\":\"102,,104\"},"
                        + "{\"code_pa\":\"APP_NAT\",\"perimetre\":\" , \"}]}]", APP);

        assertEquals(List.of("APP_LOC", "APP_RESEAU", "APP_NAT"), new ArrayList<>(data.getPerimetresByGroup().keySet()));
        assertEquals(List.of("101", "102", "104"), data.getPerimetresByGroup().get("APP_LOC"));
        assertEquals(List.of("103"), data.getPerimetresByGroup().get("APP_RESEAU"));
        assertEquals(List.of(), data.getPerimetresByGroup().get("APP_NAT"));
        // current_groups() et la réunion ne changent pas
        assertEquals(Set.of("APP_LOC", "APP_RESEAU", "APP_NAT"), data.getGroups());
        assertEquals(List.of("101", "102", "104", "103"), data.getPerimetres());
    }
}
