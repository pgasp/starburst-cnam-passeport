package io.starburst.cnam.passeport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.trino.spi.TrinoException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;

/**
 * Lecture de la réponse de l'API Passeport.
 *
 * <p>Format réel (imbriqué) : {@code [{"app":{"code_app":"X"},"listepa":[{"code_pa":"A","perimetre":"TOUS"},{"code_pa":"B"}]}]}.
 * Format plat (forme simplifiée) : {@code [{"code_pa":"A","perimetre":"TOUS"}]}.
 * Chaque élément peut porter l'une, l'autre ou les deux formes : chaque nœud est traité dans les deux cas.
 *
 * <p>Apostrophes : l'API entoure son tableau d'apostrophes littérales ({@code '[...]'}) ; la paire est retirée
 * (voir {@link #unwrapApostrophes}) avant le parsing.
 *
 * <p>JSON double-encodé : si la racine est une chaîne JSON, son contenu est relu une seule fois ; il doit alors
 * être un tableau, sinon la requête échoue.
 *
 * <p>Fail closed : toute structure inattendue lève une {@link TrinoException}. Les messages décrivent la structure
 * (rang de l'élément, nom du champ, type du nœud racine), jamais le contenu de la réponse.
 *
 * <p>Filtrage sur {@code app.code_app} : un élément dont {@code app.code_app} est présent et différent
 * (sans tenir compte de la casse ni des espaces de bord) du code application configuré est ignoré, avec un
 * avertissement. Un élément sans {@code app} (format plat) n'est pas filtré.
 */
final class PasseportResponseParser {
    private static final Logger log = Logger.getLogger(PasseportResponseParser.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_KEYS_REPORTED = 20;
    private static final int MAX_KEY_LENGTH = 64;

    private PasseportResponseParser() {}

    static PasseportAuthService.AuthData parse(String body, String expectedApplication) {
        JsonNode root = body == null ? null : readJson(unwrapApostrophes(normalize(body)));
        if (root == null) {
            throw unexpected("the response body is not valid JSON");
        }
        if (root.isTextual()) {
            // JSON double-encoded : la racine est une chaîne JSON qui contient le tableau. Décodée une seule fois.
            JsonNode decoded = readJson(root.asText());
            if (decoded == null || decoded.isMissingNode()) {
                throw unexpected("expected a JSON array at the top level, received STRING whose content is not valid JSON");
            }
            if (!decoded.isArray()) {
                throw unexpected("expected a JSON array at the top level, received STRING containing " + describe(decoded));
            }
            root = decoded;
        }
        else if (!root.isArray()) {
            throw unexpected("expected a JSON array at the top level, received " + describe(root));
        }

        Set<String> groups = new LinkedHashSet<>();
        Set<String> perimetres = new LinkedHashSet<>();
        int index = 0;
        int ignored = 0;
        for (JsonNode element : root) {
            if (!parseElement(element, index++, expectedApplication, groups, perimetres)) {
                ignored++;
            }
        }
        if (index > 0 && ignored == index) {
            log.warning("All " + index + " Passeport element(s) were ignored because their app.code_app differs from the configured "
                    + "passeport.code-application '" + expectedApplication + "': the user will have no group. "
                    + "passeport.code-application must equal the app.code_app returned by the API (case-insensitive)");
        }
        else if (index > 0 && groups.isEmpty()) {
            log.warning("The Passeport response holds " + index + " element(s) but no group (code_pa) was found: the user will have no group");
        }
        return new PasseportAuthService.AuthData(groups, java.util.List.copyOf(perimetres));
    }

    /** Retire les blancs de bord et les BOM (U+FEFF), que {@link String#strip()} ne considère pas comme des blancs. */
    private static String normalize(String body) {
        int start = 0;
        int end = body.length();
        while (start < end && isBlankOrBom(body.charAt(start))) {
            start++;
        }
        while (end > start && isBlankOrBom(body.charAt(end - 1))) {
            end--;
        }
        return body.substring(start, end);
    }

    private static boolean isBlankOrBom(char c) {
        return c == '\uFEFF' || Character.isWhitespace(c);
    }

    /**
     * L'API Passeport entoure son tableau d'apostrophes littérales : {@code '[{"app":...}]'}. Si le corps commence ET
     * finit par {@code '} (2 caractères au moins), cette unique paire est retirée, une seule fois. Une apostrophe isolée
     * ou interne n'est jamais touchée, et le parseur reste strict (pas de {@code ALLOW_SINGLE_QUOTES}).
     */
    private static String unwrapApostrophes(String body) {
        if (body.length() >= 2 && body.charAt(0) == '\'' && body.charAt(body.length() - 1) == '\'') {
            return body.substring(1, body.length() - 1);
        }
        return body;
    }

    /** Lit un document JSON ; {@code null} s'il est invalide. Un document vide donne un {@code MissingNode}. */
    private static JsonNode readJson(String text) {
        try {
            return MAPPER.readTree(text);
        }
        catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Décrit un nœud par son type seulement (STRING, OBJECT, NUMBER...), jamais par son contenu. Pour un objet, ajoute
     * ses clés de premier niveau (noms de champs, pas des données), assainies et bornées.
     */
    private static String describe(JsonNode node) {
        String type = node.getNodeType().name();
        if (!node.isObject()) {
            return type;
        }
        List<String> keys = new ArrayList<>();
        int total = 0;
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            total++;
            if (keys.size() < MAX_KEYS_REPORTED) {
                keys.add(sanitizeKey(name));
            }
        }
        String suffix = total > keys.size() ? ", ... (" + total + " keys)" : "";
        return type + " (top-level keys: " + String.join(", ", keys) + suffix + ")";
    }

    private static String sanitizeKey(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9_.-]", "?");
        return clean.length() > MAX_KEY_LENGTH ? clean.substring(0, MAX_KEY_LENGTH) + "..." : clean;
    }

    /** @return {@code false} si l'élément a été ignoré (autre application), {@code true} s'il a été lu. */
    private static boolean parseElement(JsonNode element, int index, String expectedApplication, Set<String> groups, Set<String> perimetres) {
        if (!element.isObject()) {
            throw unexpected("element #" + index + " is not a JSON object");
        }
        boolean hasCodePa = element.has("code_pa");
        boolean hasListePa = element.has("listepa");
        if (!hasCodePa && !hasListePa) {
            throw unexpected("element #" + index + " has neither 'code_pa' nor 'listepa'");
        }
        if (!matchesApplication(element, index, expectedApplication)) {
            return false;
        }
        if (hasListePa) {
            JsonNode listePa = element.get("listepa");
            if (!listePa.isArray()) {
                throw unexpected("'listepa' of element #" + index + " is not an array");
            }
            int entryIndex = 0;
            for (JsonNode entry : listePa) {
                if (!entry.isObject()) {
                    throw unexpected("entry #" + entryIndex + " of 'listepa' of element #" + index + " is not a JSON object");
                }
                parseEntry(entry, "entry #" + entryIndex + " of 'listepa' of element #" + index, groups, perimetres);
                entryIndex++;
            }
        }
        if (hasCodePa) {
            parseEntry(element, "element #" + index, groups, perimetres);
        }
        return true;
    }

    private static boolean matchesApplication(JsonNode element, int index, String expectedApplication) {
        JsonNode app = element.get("app");
        if (app == null || app.isNull()) {
            return true;
        }
        if (!app.isObject()) {
            throw unexpected("'app' of element #" + index + " is not a JSON object");
        }
        JsonNode codeApp = app.get("code_app");
        if (codeApp == null || codeApp.isNull()) {
            return true;
        }
        if (!codeApp.isTextual()) {
            throw unexpected("'app.code_app' of element #" + index + " is not a string");
        }
        if (codeApp.asText().strip().equalsIgnoreCase(expectedApplication.strip())) {
            return true;
        }
        log.warning("Ignoring Passeport element #" + index + ": app.code_app '" + codeApp.asText()
                + "' differs from the configured passeport.code-application '" + expectedApplication + "'");
        return false;
    }

    private static void parseEntry(JsonNode entry, String where, Set<String> groups, Set<String> perimetres) {
        JsonNode codePa = entry.get("code_pa");
        if (codePa == null || !codePa.isTextual() || codePa.asText().isBlank()) {
            throw unexpected("'code_pa' of " + where + " is missing, empty or not a string");
        }
        groups.add(codePa.asText());

        JsonNode perimetre = entry.get("perimetre");
        if (perimetre == null || perimetre.isNull()) {
            return;
        }
        if (!perimetre.isTextual() && !perimetre.isNumber()) {
            throw unexpected("'perimetre' of " + where + " is neither a string nor a number");
        }
        // "011,717" -> 011 et 717 ; valeurs vides ignorées ; doublons éliminés en gardant l'ordre d'apparition.
        for (String part : perimetre.asText().split(",")) {
            String value = part.strip();
            if (!value.isEmpty()) {
                perimetres.add(value);
            }
        }
    }

    private static TrinoException unexpected(String detail) {
        return new TrinoException(GENERIC_INTERNAL_ERROR, "Unexpected response format from Passeport API: " + detail);
    }
}
