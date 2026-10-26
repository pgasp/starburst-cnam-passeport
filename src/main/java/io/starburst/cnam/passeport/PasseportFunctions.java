package io.starburst.cnam.passeport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
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
 * <h2>Modèle de profils (depuis 3.0.24)</h2>
 * Le nom d'un rôle BIAC est égal au nom d'un groupe Passeport ({@code code_pa}). {@code passeport_perimetre()} renvoie le
 * périmètre du <b>seul</b> groupe dont le nom est le rôle actif ; dans tous les autres cas, un tableau vide.
 * (Changement de comportement : jusqu'à la 3.0.23, la fonction renvoyait la réunion des périmètres de tous les groupes.)
 *
 * <h2>Hypothèse sur les rôles actifs (à confirmer sur le lab)</h2>
 * Les rôles actifs sont lus dans {@code session.getIdentity().getEnabledSystemRoles()}. Mesuré : sous
 * {@code SET ROLE sysadmin}, la valeur est {@code [sysadmin]}. Ce qui est renvoyé sans {@code SET ROLE} et sous
 * {@code SET ROLE ALL} n'a <b>pas</b> pu être mesuré. La logique ne dépend donc que de l'hypothèse minimale suivante :
 * les rôles {@code public} et {@code sysadmin} sont ignorés (casse et espaces de bord indifférents), et il faut qu'il
 * reste <b>exactement un</b> rôle pour qu'un périmètre soit renvoyé. Si une session sans {@code SET ROLE} ou sous
 * {@code SET ROLE ALL} expose plusieurs rôles (ou aucun), le résultat est {@code []} (fail closed) ; si elle n'en expose
 * qu'un, ce rôle est pris pour le profil actif.
 *
 * <h2>Où les fonctions s'exécutent</h2>
 * Le cache et le group provider n'existent que sur le coordinator. Ces fonctions sont déterministes (valeur par défaut) :
 * l'optimiseur de Trino évalue donc un appel à arguments constants à la planification, sur le coordinator, avant l'envoi
 * aux workers. Si l'appel était évalué sur un worker, le service est absent : périmètre vide, invalidation à {@code false}.
 */
public final class PasseportFunctions {
    private static final Logger log = Logger.getLogger(PasseportFunctions.class.getName());

    /** Rôles qui ne désignent pas un profil : ils ne comptent pas dans le décompte des rôles actifs. */
    private static final Set<String> NON_PROFILE_ROLES = Set.of("public", "sysadmin");

    private PasseportFunctions() {}

    @ScalarFunction("passeport_perimetre")
    @Description("Perimetre of the Passeport group named like the single active role; empty array if several roles are active, none, or no group matches")
    @SqlType("array(varchar)")
    public static Block getPasseportPerimetre(ConnectorSession session) {
        return toBlock(perimetreOfActiveProfile(session, Optional.empty()));
    }

    @ScalarFunction("passeport_perimetre")
    @Description("Perimetre of the given Passeport group, only if the user belongs to it and its role is the single active role; empty array otherwise")
    @SqlType("array(varchar)")
    public static Block getPasseportPerimetreOfGroup(ConnectorSession session, @SqlNullable @SqlType(StandardTypes.VARCHAR) Slice codePa) {
        if (codePa == null) {
            return toBlock(List.of());
        }
        return toBlock(perimetreOfActiveProfile(session, Optional.of(codePa.toStringUtf8())));
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

    private static List<String> perimetreOfActiveProfile(ConnectorSession session, Optional<String> requestedGroup) {
        if (session == null || session.getUser() == null || session.getIdentity() == null) {
            return List.of();
        }
        PasseportAuthService authService = PasseportAuthService.getGlobalInstance();
        if (authService == null) {
            return List.of();
        }
        Map<String, List<String>> byGroup = authService.getPerimetresByGroup(session.getUser());
        return resolvePerimetre(session.getIdentity().getEnabledSystemRoles(), byGroup, requestedGroup);
    }

    /**
     * Cœur de la règle, sans dépendance à SEP.
     *
     * @param enabledRoles rôles actifs de la session
     * @param perimetresByGroup périmètre de chaque groupe de l'utilisateur ({@code code_pa} vers périmètre)
     * @param requestedGroup groupe demandé par la surcharge, vide pour {@code passeport_perimetre()}
     * @return le périmètre du groupe du rôle actif ; liste vide dans tous les autres cas. Un groupe dont le périmètre
     *         est vide donne aussi une liste vide : cela signifie « aucune caisse » (jamais « toutes »).
     */
    static List<String> resolvePerimetre(Set<String> enabledRoles, Map<String, List<String>> perimetresByGroup, Optional<String> requestedGroup) {
        Optional<String> activeProfile = activeProfile(enabledRoles);
        if (activeProfile.isEmpty()) {
            return List.of();
        }
        String profile = activeProfile.get();
        // Surcharge : le groupe demandé doit être le profil actif, sinon rien (jamais le périmètre d'un autre profil).
        if (requestedGroup.isPresent() && !sameName(requestedGroup.get(), profile)) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String group : perimetresByGroup.keySet()) {
            if (sameName(group, profile)) {
                matches.add(group);
            }
        }
        if (matches.isEmpty()) {
            return List.of();
        }
        if (matches.size() > 1) {
            // Deux code_pa qui ne diffèrent que par la casse ou les blancs de bord : on ne devine pas lequel est le bon.
            log.warning("Ambiguous match: " + matches.size() + " Passeport groups equal the active role '" + profile
                    + "' ignoring case and surrounding spaces; returning an empty perimetre");
            return List.of();
        }
        return perimetresByGroup.get(matches.get(0));
    }

    /**
     * Le profil actif : l'unique rôle restant après retrait de {@code public} et {@code sysadmin}. Plusieurs rôles ou
     * aucun : pas de profil. Les rôles sont comptés tels que reçus (deux rôles qui ne diffèrent que par la casse
     * comptent pour deux).
     */
    static Optional<String> activeProfile(Set<String> enabledRoles) {
        if (enabledRoles == null) {
            return Optional.empty();
        }
        List<String> profiles = new ArrayList<>();
        for (String role : enabledRoles) {
            if (role == null || !NON_PROFILE_ROLES.contains(role.strip().toLowerCase(Locale.ROOT))) {
                profiles.add(role);
            }
        }
        if (profiles.size() != 1 || profiles.get(0) == null) {
            return Optional.empty();
        }
        return Optional.of(profiles.get(0));
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
