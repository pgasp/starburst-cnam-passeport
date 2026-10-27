package io.starburst.cnam.passeport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.StandardErrorCode;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;
import io.trino.spi.type.VarcharType;

/**
 * Fonctions SQL liées à Passeport.
 *
 * <h2>Modèle de profils (depuis 3.1.0)</h2>
 * Le nom d'un rôle BIAC est égal au nom d'un groupe Passeport ({@code code_pa}). Deux fonctions, aux rôles distincts :
 * <ul>
 *   <li>{@code current_passeport_perimetre()} : périmètre du <b>profil actif</b>, c'est-à-dire du seul groupe dont le nom
 *       est le rôle actif. À utiliser dans les row filters. <b>Plusieurs profils actifs : la requête échoue</b>
 *       ({@code NOT_SUPPORTED}, « Unsupported multi perimetre ») au lieu de renvoyer un tableau vide.</li>
 *   <li>{@code passeport_perimetres(code_pa)} : périmètre du groupe donné si l'utilisateur appelant en est membre,
 *       sans consulter le profil actif. Pour le diagnostic ; elle ne vérifie pas le profil actif et ne doit pas servir
 *       de row filter à elle seule.</li>
 * </ul>
 * Les anciennes {@code passeport_perimetre()} et {@code passeport_perimetre(varchar)} (3.0.24) sont supprimées (changement
 * cassant). Dans tous les cas, un périmètre vide signifie « aucune caisse », jamais « toutes ».
 *
 * <h2>Rôles actifs</h2>
 * Les rôles actifs sont lus dans {@code session.getIdentity().getEnabledSystemRoles()}. Mesuré : sous
 * {@code SET ROLE sysadmin}, la valeur est {@code [sysadmin]}. Ce qui est renvoyé sans {@code SET ROLE} et sous
 * {@code SET ROLE ALL} n'a <b>pas</b> pu être mesuré. Les rôles {@code public} et {@code sysadmin} sont ignorés (casse et
 * espaces de bord indifférents) ; les autres sont des profils. Un profil : il est le profil actif. Aucun : pas de profil
 * actif, résultat {@code []}. Plusieurs : erreur. Une session sans {@code SET ROLE} qui active plusieurs rôles voit donc ses
 * requêtes passant par un row filter échouer : il faut choisir un profil avec {@code SET ROLE <profil>}.
 *
 * <h2>Où les fonctions s'exécutent</h2>
 * Le cache et le group provider n'existent que sur le coordinator. Ces fonctions sont déterministes (valeur par défaut) :
 * l'optimiseur de Trino évalue donc un appel à arguments constants à la planification, sur le coordinator, avant l'envoi
 * aux workers. Si l'appel était évalué sur un worker, le service est absent : périmètre vide, invalidation à {@code false}.
 * Le contrôle « plusieurs profils » de {@code current_passeport_perimetre()} ne dépend que de la session et est fait
 * <b>avant</b> toute lecture du service : l'erreur sort aussi sur un nœud sans service, elle n'est pas masquée par un
 * tableau vide.
 */
public final class PasseportFunctions {
    private static final Logger log = Logger.getLogger(PasseportFunctions.class.getName());

    /** Rôles qui ne désignent pas un profil : ils ne comptent pas dans le décompte des rôles actifs. */
    private static final Set<String> NON_PROFILE_ROLES = Set.of("public", "sysadmin");

    private PasseportFunctions() {}

    @ScalarFunction("current_passeport_perimetre")
    @Description("Perimetre of the Passeport group named like the single active profile (role); empty array if no profile is active or no group matches; fails if several profiles are active")
    @SqlType("array(varchar)")
    public static Block getCurrentPasseportPerimetre(ConnectorSession session) {
        return toBlock(currentPerimetre(session));
    }

    @ScalarFunction("passeport_perimetres")
    @Description("Perimetre of the given Passeport group if the calling user is a member of it; empty array otherwise. Does not check the active profile")
    @SqlType("array(varchar)")
    public static Block getPasseportPerimetresOfGroup(ConnectorSession session, @SqlNullable @SqlType(StandardTypes.VARCHAR) Slice codePa) {
        if (codePa == null) {
            return toBlock(List.of());
        }
        return toBlock(perimetresOfGroup(session, codePa.toStringUtf8()));
    }

    /**
     * Invalide le cache de l'utilisateur courant, et seulement le sien. Pas de variante globale ni avec un nom
     * d'utilisateur en argument. Renvoie {@code true} si l'invalidation a été faite sur ce nœud, {@code false} si le
     * service est absent (nœud sans group provider, par exemple un worker) ou si l'utilisateur est inexploitable.
     *
     * <p>La fonction reste déterministe (défaut) : c'est ce qui la fait évaluer à la planification, sur le coordinator,
     * où vit le cache. Déclarée non déterministe, elle serait évaluée à l'exécution, possiblement sur un worker
     * (réponse {@code false}, rien d'invalidé). Conséquence : l'appeler seule, {@code SELECT passeport_cache_invalidate()}.
     */
    @ScalarFunction("passeport_cache_invalidate")
    @Description("Invalidates the Passeport cache entry of the current user only; true if done on this node")
    @SqlType(StandardTypes.BOOLEAN)
    public static boolean invalidateCurrentUserCache(ConnectorSession session) {
        if (session == null) {
            return false;
        }
        PasseportAuthService authService = PasseportAuthService.getGlobalInstance();
        if (authService == null) {
            log.warning("passeport_cache_invalidate() evaluated on a node without Passeport service (worker?): nothing invalidated");
            return false;
        }
        boolean done = authService.invalidate(session.getUser());
        if (done) {
            log.fine("Passeport cache entry invalidated for user: " + session.getUser());
        }
        return done;
    }

    @ScalarFunction("current_user_biac_roles")
    @Description("Returns the list of enabled system roles (BIAC) for the current user from the session identity")
    @SqlType("array(varchar)")
    public static Block getPassportBiacRoles(ConnectorSession session) {
        if (session == null || session.getIdentity() == null) {
            return toBlock(List.of());
        }
        Set<String> roles = session.getIdentity().getEnabledSystemRoles();
        if (roles == null || roles.isEmpty()) {
            return toBlock(List.of());
        }
        return toBlock(new ArrayList<>(roles));
    }

    private static List<String> currentPerimetre(ConnectorSession session) {
        if (session == null || session.getUser() == null || session.getIdentity() == null) {
            return List.of();
        }
        // Contrôle multi-profils d'abord : il ne dépend que de la session, il doit sortir même si le service est absent.
        Optional<String> profile = requireAtMostOneProfile(session.getIdentity().getEnabledSystemRoles());
        if (profile.isEmpty()) {
            return List.of();
        }
        PasseportAuthService authService = PasseportAuthService.getGlobalInstance();
        if (authService == null) {
            return List.of();
        }
        return resolveGroupPerimetre(authService.getPerimetresByGroup(session.getUser()), profile.get());
    }

    private static List<String> perimetresOfGroup(ConnectorSession session, String requestedGroup) {
        if (session == null || session.getUser() == null) {
            return List.of();
        }
        PasseportAuthService authService = PasseportAuthService.getGlobalInstance();
        if (authService == null) {
            return List.of();
        }
        return resolveGroupPerimetre(authService.getPerimetresByGroup(session.getUser()), requestedGroup);
    }

    /**
     * Cœur de {@code current_passeport_perimetre()}, sans dépendance à SEP.
     *
     * @param enabledRoles rôles actifs de la session
     * @param perimetresByGroup périmètre de chaque groupe de l'utilisateur ({@code code_pa} vers périmètre)
     * @return le périmètre du groupe du profil actif ; liste vide si aucun profil n'est actif ou si aucun groupe ne
     *         porte son nom. Un groupe dont le périmètre est vide donne aussi une liste vide : « aucune caisse ».
     * @throws TrinoException {@code NOT_SUPPORTED} si plusieurs profils sont actifs
     */
    static List<String> resolveCurrentPerimetre(Set<String> enabledRoles, Map<String, List<String>> perimetresByGroup) {
        Optional<String> profile = requireAtMostOneProfile(enabledRoles);
        if (profile.isEmpty()) {
            return List.of();
        }
        return resolveGroupPerimetre(perimetresByGroup, profile.get());
    }

    /**
     * Périmètre du groupe {@code requestedGroup} parmi les groupes de l'utilisateur (donc : seulement s'il en est membre,
     * l'appelant n'obtient jamais le périmètre d'un groupe qui n'est pas le sien). Casse et espaces de bord ignorés.
     * Aucun groupe, ou plusieurs qui ne diffèrent que par la casse ou les blancs de bord : liste vide (le second cas avec
     * un WARNING : on ne devine pas lequel est le bon).
     */
    static List<String> resolveGroupPerimetre(Map<String, List<String>> perimetresByGroup, String requestedGroup) {
        if (requestedGroup == null || perimetresByGroup == null) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String group : perimetresByGroup.keySet()) {
            if (group != null && sameName(group, requestedGroup)) {
                matches.add(group);
            }
        }
        if (matches.isEmpty()) {
            return List.of();
        }
        if (matches.size() > 1) {
            log.warning("Ambiguous match: " + matches.size() + " Passeport groups equal '" + requestedGroup
                    + "' ignoring case and surrounding spaces; returning an empty perimetre");
            return List.of();
        }
        List<String> perimetre = perimetresByGroup.get(matches.get(0));
        return perimetre == null ? List.of() : perimetre;
    }

    /**
     * Le profil actif : l'unique rôle restant après retrait de {@code public} et {@code sysadmin}. Aucun : vide.
     * Plusieurs : {@code TrinoException(NOT_SUPPORTED)} qui ne liste que les rôles de l'appelant. Les rôles sont comptés
     * tels que reçus (deux rôles qui ne diffèrent que par la casse comptent pour deux).
     */
    static Optional<String> requireAtMostOneProfile(Set<String> enabledRoles) {
        List<String> profiles = activeProfiles(enabledRoles);
        if (profiles.size() > 1) {
            throw new TrinoException(StandardErrorCode.NOT_SUPPORTED,
                    "Unsupported multi perimetre: " + profiles.size() + " profils actifs " + profiles
                            + ". Choisissez un profil avec SET ROLE <profil>");
        }
        return profiles.isEmpty() ? Optional.empty() : Optional.of(profiles.get(0));
    }

    /** Rôles actifs hors {@code public} et {@code sysadmin} (et hors valeur nulle), triés. */
    static List<String> activeProfiles(Set<String> enabledRoles) {
        if (enabledRoles == null) {
            return List.of();
        }
        List<String> profiles = new ArrayList<>();
        for (String role : enabledRoles) {
            if (role != null && !NON_PROFILE_ROLES.contains(role.strip().toLowerCase(Locale.ROOT))) {
                profiles.add(role);
            }
        }
        Collections.sort(profiles);
        return profiles;
    }

    private static boolean sameName(String left, String right) {
        return left.strip().equalsIgnoreCase(right.strip());
    }

    private static Block toBlock(List<String> values) {
        BlockBuilder blockBuilder = VarcharType.VARCHAR.createBlockBuilder(null, values.size());
        for (String value : values) {
            VarcharType.VARCHAR.writeSlice(blockBuilder, Slices.utf8Slice(value));
        }
        return blockBuilder.build();
    }
}
