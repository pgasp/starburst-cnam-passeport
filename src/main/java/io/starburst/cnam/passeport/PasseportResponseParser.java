package io.starburst.cnam.passeport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.trino.spi.TrinoException;

import java.util.LinkedHashSet;
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
 * <p>Fail closed : toute structure inattendue lève une {@link TrinoException}. Les messages décrivent la structure
 * (rang de l'élément, nom du champ), jamais le contenu de la réponse.
 *
 * <p>Filtrage sur {@code app.code_app} : un élément dont {@code app.code_app} est présent et différent
 * (sans tenir compte de la casse ni des espaces de bord) du code application configuré est ignoré, avec un
 * avertissement. Un élément sans {@code app} (format plat) n'est pas filtré.
 */
final class PasseportResponseParser {
    private static final Logger log = Logger.getLogger(PasseportResponseParser.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PasseportResponseParser() {}

    static PasseportAuthService.AuthData parse(String body, String expectedApplication) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        }
        catch (JsonProcessingException e) {
            throw unexpected("the response body is not valid JSON");
        }
        if (root == null || !root.isArray()) {
            throw unexpected("expected a JSON array at the top level");
        }

        Set<String> groups = new LinkedHashSet<>();
        Set<String> perimetres = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode element : root) {
            parseElement(element, index++, expectedApplication, groups, perimetres);
        }
        return new PasseportAuthService.AuthData(groups, java.util.List.copyOf(perimetres));
    }

    private static void parseElement(JsonNode element, int index, String expectedApplication, Set<String> groups, Set<String> perimetres) {
        if (!element.isObject()) {
            throw unexpected("element #" + index + " is not a JSON object");
        }
        boolean hasCodePa = element.has("code_pa");
        boolean hasListePa = element.has("listepa");
        if (!hasCodePa && !hasListePa) {
            throw unexpected("element #" + index + " has neither 'code_pa' nor 'listepa'");
        }
        if (!matchesApplication(element, index, expectedApplication)) {
            return;
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
