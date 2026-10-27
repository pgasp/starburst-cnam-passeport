package io.starburst.cnam.passeport;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.airlift.slice.Slices;
import io.trino.spi.StandardErrorCode;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.security.ConnectorIdentity;
import io.trino.spi.type.VarcharType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Modèle de profils (3.1.0) : {@code current_passeport_perimetre()} (profil actif, erreur si plusieurs profils) et
 * {@code passeport_perimetres(code_pa)} (groupe dont l'appelant est membre).
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

    /** Deux code_pa qui ne diffèrent que par la casse. */
    private static final Map<String, List<String>> AMBIGUOUS = new LinkedHashMap<>();

    static {
        AMBIGUOUS.put("APP_LOC", List.of("101"));
        AMBIGUOUS.put("app_loc", List.of("102"));
    }

    @AfterEach
    public void restoreGlobalInstance() {
        PasseportAuthService.clearGlobalInstance();
    }

    // ---- règle pure (sans SEP) : current_passeport_perimetre ----

    private static List<String> current(Set<String> roles) {
        return PasseportFunctions.resolveCurrentPerimetre(roles, GROUPS);
    }

    private static TrinoException assertMultiProfile(Set<String> roles) {
        TrinoException e = assertThrows(TrinoException.class, () -> current(roles));
        assertEquals(StandardErrorCode.NOT_SUPPORTED.toErrorCode(), e.getErrorCode());
        return e;
    }

    @Test
    public void singleMatchingProfileGivesThatGroupPerimetreOnly() {
        // APP_LOC (101) seul : pas les 6 caisses de APP_RESEAU, même si l'utilisateur appartient aux deux groupes.
        assertEquals(List.of("101"), current(Set.of("APP_LOC")));
        assertEquals(List.of("101", "102", "103", "104", "105", "106"), current(Set.of("APP_RESEAU")));
    }

    @Test
    public void noProfileGivesEmpty() {
        assertEquals(List.of(), current(Set.of()));
        assertEquals(List.of(), PasseportFunctions.resolveCurrentPerimetre(null, GROUPS));
    }

    @Test
    public void twoOrMoreProfilesFailWithExactMessage() {
        TrinoException e = assertMultiProfile(Set.of("APP_RESEAU", "APP_LOC"));
        // rôles triés, uniquement ceux de l'appelant (pas les autres groupes connus)
        assertEquals("Unsupported multi perimetre: 2 profils actifs [APP_LOC, APP_RESEAU]. Choisissez un profil avec SET ROLE <profil>", e.getMessage());

        TrinoException three = assertMultiProfile(Set.of("APP_RESEAU", "APP_LOC", "APP_NAT"));
        assertEquals("Unsupported multi perimetre: 3 profils actifs [APP_LOC, APP_NAT, APP_RESEAU]. Choisissez un profil avec SET ROLE <profil>", three.getMessage());

        // deux rôles qui ne diffèrent que par la casse comptent pour deux
        assertMultiProfile(Set.of("APP_LOC", "app_loc"));
    }

    @Test
    public void multiProfileErrorDoesNotListPublicOrSysadmin() {
        TrinoException e = assertMultiProfile(Set.of("public", "sysadmin", "APP_LOC", "APP_RESEAU"));
        assertEquals("Unsupported multi perimetre: 2 profils actifs [APP_LOC, APP_RESEAU]. Choisissez un profil avec SET ROLE <profil>", e.getMessage());
    }

    @Test
    public void publicAndSysadminAreIgnored() {
        assertEquals(List.of("101"), current(Set.of("public", "APP_LOC")));
        assertEquals(List.of("101"), current(Set.of("PUBLIC", "Sysadmin", "APP_LOC")));
        assertEquals(List.of("101"), current(Set.of(" sysadmin ", "APP_LOC")));
        // sans autre rôle : aucun profil, pas d'erreur
        assertEquals(List.of(), current(Set.of("sysadmin")));
        assertEquals(List.of(), current(Set.of("public")));
        assertEquals(List.of(), current(Set.of("public", "sysadmin")));
        assertEquals(List.of(), current(Set.of(" PUBLIC ", "SYSADMIN")));
    }

    @Test
    public void roleMatchingNoGroupGivesEmpty() {
        assertEquals(List.of(), current(Set.of("APP_AUTRE")));
        // un groupe que l'utilisateur n'a pas, même s'il existe ailleurs
        assertEquals(List.of(), PasseportFunctions.resolveCurrentPerimetre(Set.of("APP_LOC"), Map.of("APP_RESEAU", List.of("101"))));
        assertEquals(List.of(), PasseportFunctions.resolveCurrentPerimetre(Set.of("APP_LOC"), Map.of()));
    }

    @Test
    public void matchingIsCaseInsensitiveAndIgnoresSurroundingSpaces() {
        assertEquals(List.of("101"), current(Set.of("app_loc")));
        assertEquals(List.of("101"), current(Set.of("  App_Loc ")));
        assertEquals(List.of("101"), PasseportFunctions.resolveCurrentPerimetre(Set.of("APP_LOC"), Map.of(" app_loc ", List.of("101"))));
        // pas de correspondance partielle
        assertEquals(List.of(), current(Set.of("APP_LO")));
        assertEquals(List.of(), current(Set.of("APP_LOC_X")));
    }

    @Test
    public void groupWithEmptyPerimetreMeansNoCaisseNotAll() {
        assertEquals(List.of(), current(Set.of("APP_NAT")));
        // contre-épreuve : le row filter contains(current_passeport_perimetre(), caisse) ne laisse alors passer aucune ligne
        assertFalse(current(Set.of("APP_NAT")).contains("101"));
    }

    @Test
    public void ambiguousGroupNamesGiveEmpty() {
        // deux code_pa qui ne diffèrent que par la casse : on ne choisit pas
        assertEquals(List.of(), PasseportFunctions.resolveCurrentPerimetre(Set.of("APP_LOC"), AMBIGUOUS));
    }

    // ---- règle pure : passeport_perimetres(code_pa) ----

    @Test
    public void groupPerimetreRequiresMembership() {
        assertEquals(List.of("101"), PasseportFunctions.resolveGroupPerimetre(GROUPS, "APP_LOC"));
        assertEquals(List.of("101"), PasseportFunctions.resolveGroupPerimetre(GROUPS, " app_loc "));
        assertEquals(List.of("101", "102", "103", "104", "105", "106"), PasseportFunctions.resolveGroupPerimetre(GROUPS, "APP_RESEAU"));
        // groupe non membre : jamais son périmètre
        assertEquals(List.of(), PasseportFunctions.resolveGroupPerimetre(GROUPS, "APP_AUTRE"));
        assertEquals(List.of(), PasseportFunctions.resolveGroupPerimetre(Map.of("APP_RESEAU", List.of("101")), "APP_LOC"));
        assertEquals(List.of(), PasseportFunctions.resolveGroupPerimetre(Map.of(), "APP_LOC"));
        // pas de correspondance partielle
        assertEquals(List.of(), PasseportFunctions.resolveGroupPerimetre(GROUPS, "APP_LO"));
    }

    @Test
    public void groupPerimetreEmptyOrAmbiguousOrNullGivesEmpty() {
        assertEquals(List.of(), PasseportFunctions.resolveGroupPerimetre(GROUPS, "APP_NAT"));
        assertEquals(List.of(), PasseportFunctions.resolveGroupPerimetre(AMBIGUOUS, "APP_LOC"));
        assertEquals(List.of(), PasseportFunctions.resolveGroupPerimetre(GROUPS, null));
        assertEquals(List.of(), PasseportFunctions.resolveGroupPerimetre(null, "APP_LOC"));
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

    private static List<String> currentPerimetre(ConnectorSession session) {
        return values(PasseportFunctions.getCurrentPasseportPerimetre(session));
    }

    private static List<String> perimetres(ConnectorSession session, String codePa) {
        return values(PasseportFunctions.getPasseportPerimetresOfGroup(session, codePa == null ? null : Slices.utf8Slice(codePa)));
    }

    @Test
    public void currentPerimetreEndToEnd(WireMockRuntimeInfo wm) {
        stubUser(USER, BODY);
        new PasseportAuthService(config(wm));

        // 1 profil, avec ou sans public / sysadmin
        assertEquals(List.of("101"), currentPerimetre(session(USER, "APP_LOC")));
        assertEquals(List.of("101"), currentPerimetre(session(USER, "public", "APP_LOC")));
        assertEquals(List.of("101"), currentPerimetre(session(USER, "public", "sysadmin", "APP_LOC")));
        // 0 profil
        assertEquals(List.of(), currentPerimetre(session(USER)));
        assertEquals(List.of(), currentPerimetre(session(USER, "sysadmin")));
        assertEquals(List.of(), currentPerimetre(session(USER, "public")));
        assertEquals(List.of(), currentPerimetre(session(USER, "public", "sysadmin")));
        // 1 profil sans groupe correspondant
        assertEquals(List.of(), currentPerimetre(session(USER, "APP_AUTRE")));
        // groupe à périmètre vide
        assertEquals(List.of(), currentPerimetre(session(USER, "APP_NAT")));

        // 2+ profils : erreur, y compris avec public / sysadmin en plus
        TrinoException two = assertThrows(TrinoException.class, () -> currentPerimetre(session(USER, "APP_LOC", "APP_RESEAU")));
        assertEquals(StandardErrorCode.NOT_SUPPORTED.toErrorCode(), two.getErrorCode());
        assertEquals("Unsupported multi perimetre: 2 profils actifs [APP_LOC, APP_RESEAU]. Choisissez un profil avec SET ROLE <profil>", two.getMessage());
        assertThrows(TrinoException.class, () -> currentPerimetre(session(USER, "public", "sysadmin", "APP_LOC", "APP_RESEAU")));

        // sessions inexploitables
        assertEquals(List.of(), currentPerimetre(null));
        assertEquals(List.of(), currentPerimetre(session(null)));

        // un seul appel API pour toutes ces évaluations (cache)
        verify(1, getRequestedFor(urlEqualTo(BASE_PATH + "/" + USER + "/" + APP)));
    }

    @Test
    public void multiProfileCheckHappensBeforeServiceLookup() {
        // Service absent (cas d'une évaluation sur un worker) : l'erreur multi-profils sort quand même, elle n'est pas
        // masquée par un tableau vide.
        PasseportAuthService.clearGlobalInstance();
        assertNull(PasseportAuthService.getGlobalInstance());
        TrinoException e = assertThrows(TrinoException.class, () -> currentPerimetre(session(USER, "APP_LOC", "APP_RESEAU")));
        assertEquals(StandardErrorCode.NOT_SUPPORTED.toErrorCode(), e.getErrorCode());
        assertEquals("Unsupported multi perimetre: 2 profils actifs [APP_LOC, APP_RESEAU]. Choisissez un profil avec SET ROLE <profil>", e.getMessage());

        // contre-épreuve : un seul profil sans service reste fail closed (vide)
        assertEquals(List.of(), currentPerimetre(session(USER, "APP_LOC")));
        assertEquals(List.of(), currentPerimetre(session(USER)));
    }

    @Test
    public void multiProfileErrorOnlyListsTheCallersRoles(WireMockRuntimeInfo wm) {
        stubUser(USER, BODY);
        new PasseportAuthService(config(wm));
        // l'utilisateur appartient aussi à APP_NAT, mais ne l'a pas activé : il ne figure pas dans le message
        TrinoException e = assertThrows(TrinoException.class, () -> currentPerimetre(session(USER, "APP_RESEAU", "APP_LOC")));
        assertFalse(e.getMessage().contains("APP_NAT"));
    }

    @Test
    public void passeportPerimetresEndToEnd(WireMockRuntimeInfo wm) {
        stubUser(USER, BODY);
        new PasseportAuthService(config(wm));

        // membre : périmètre du groupe, quel que soit le profil actif (aucun, autre, plusieurs, sysadmin)
        assertEquals(List.of("101"), perimetres(session(USER, "APP_LOC"), "APP_LOC"));
        assertEquals(List.of("101"), perimetres(session(USER), "app_loc"));
        assertEquals(List.of("101"), perimetres(session(USER, "APP_RESEAU"), "APP_LOC"));
        assertEquals(List.of("101"), perimetres(session(USER, "APP_LOC", "APP_RESEAU"), "APP_LOC"));
        assertEquals(List.of("101"), perimetres(session(USER, "sysadmin", "public"), " APP_LOC "));
        assertEquals(List.of("101", "102", "103", "104", "105", "106"), perimetres(session(USER, "APP_LOC"), "APP_RESEAU"));
        // groupe à périmètre vide
        assertEquals(List.of(), perimetres(session(USER, "APP_NAT"), "APP_NAT"));
        // non membre : jamais le périmètre d'un groupe qui n'est pas le sien, même si le rôle porte ce nom
        assertEquals(List.of(), perimetres(session(USER, "APP_AUTRE"), "APP_AUTRE"));
        assertEquals(List.of(), perimetres(session(USER), "APP_AUTRE"));
        // NULL
        assertEquals(List.of(), perimetres(session(USER, "APP_LOC"), null));
        // sessions inexploitables
        assertEquals(List.of(), perimetres(null, "APP_LOC"));
        assertEquals(List.of(), perimetres(session(null), "APP_LOC"));

        verify(1, getRequestedFor(urlEqualTo(BASE_PATH + "/" + USER + "/" + APP)));
    }

    @Test
    public void passeportPerimetresIsPerUserNotPerRequestedName(WireMockRuntimeInfo wm) {
        stubUser(USER, BODY);
        stubUser(OTHER_USER, "[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":[{\"code_pa\":\"APP_RESEAU\",\"perimetre\":\"106\"}]}]");
        new PasseportAuthService(config(wm));

        // OTHER_USER n'est pas membre de APP_LOC : il n'obtient pas le périmètre de USER pour ce groupe
        assertEquals(List.of(), perimetres(session(OTHER_USER, "APP_LOC"), "APP_LOC"));
        assertEquals(List.of("106"), perimetres(session(OTHER_USER), "APP_RESEAU"));
    }

    @Test
    public void passeportPerimetresDuplicateCodePaIgnoringCaseGivesEmpty(WireMockRuntimeInfo wm) {
        stubUser(USER, "[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":["
                + "{\"code_pa\":\"APP_LOC\",\"perimetre\":\"101\"},{\"code_pa\":\"app_loc\",\"perimetre\":\"102\"}]}]");
        new PasseportAuthService(config(wm));
        assertEquals(List.of(), perimetres(session(USER, "APP_LOC"), "APP_LOC"));
        assertEquals(List.of(), currentPerimetre(session(USER, "APP_LOC")));
    }

    @Test
    public void perimetresAreEmptyWithoutServiceOrUser() {
        PasseportAuthService.clearGlobalInstance();
        assertEquals(List.of(), currentPerimetre(session(USER, "APP_LOC")));
        assertEquals(List.of(), currentPerimetre(null));
        assertEquals(List.of(), perimetres(session(USER, "APP_LOC"), "APP_LOC"));
        assertEquals(List.of(), perimetres(null, "APP_LOC"));
    }

    @Test
    public void oldPerimetreFunctionsAreRemoved() {
        for (var method : PasseportFunctions.class.getDeclaredMethods()) {
            var annotation = method.getAnnotation(io.trino.spi.function.ScalarFunction.class);
            if (annotation != null) {
                assertNotEquals("passeport_perimetre", annotation.value(), "passeport_perimetre doit être supprimée");
            }
        }
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
        assertEquals(List.of("101"), currentPerimetre(session(USER, "APP_LOC")));

        stubUser(USER, "[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":[{\"code_pa\":\"APP_LOC\",\"perimetre\":\"101,107\"}]}]");
        // encore l'ancienne valeur tant que le cache n'est pas purgé
        assertEquals(List.of("101"), currentPerimetre(session(USER, "APP_LOC")));

        assertTrue(PasseportFunctions.invalidateCurrentUserCache(session(USER, "APP_LOC")));
        assertEquals(List.of("101", "107"), currentPerimetre(session(USER, "APP_LOC")));
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
