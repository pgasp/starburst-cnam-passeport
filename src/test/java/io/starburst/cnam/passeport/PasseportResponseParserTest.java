package io.starburst.cnam.passeport;

import io.trino.spi.TrinoException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PasseportResponseParserTest {

    private static PasseportAuthService.AuthData parse(String body) {
        return PasseportResponseParser.parse(body, "SY_APP");
    }

    @Test
    public void appCodeIsComparedIgnoringCaseAndSurroundingSpaces() {
        PasseportAuthService.AuthData data = parse("[{\"app\":{\"code_app\":\" sy_app \"},\"listepa\":[{\"code_pa\":\"A\",\"perimetre\":\"1\"}]}]");
        assertEquals(Set.of("A"), data.getGroups());
        assertEquals(List.of("1"), data.getPerimetres());
    }

    @Test
    public void elementOfAnotherApplicationIsIgnored() {
        PasseportAuthService.AuthData data = parse("["
                + "{\"app\":{\"code_app\":\"SY_OTHER\"},\"listepa\":[{\"code_pa\":\"OTHER\",\"perimetre\":\"999\"}]},"
                + "{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":[{\"code_pa\":\"MINE\"}]}]");
        assertEquals(Set.of("MINE"), data.getGroups());
        assertTrue(data.getPerimetres().isEmpty());
    }

    @Test
    public void appWithoutCodeAppIsNotFiltered() {
        PasseportAuthService.AuthData data = parse("[{\"app\":{\"compl1\":\"%\"},\"listepa\":[{\"code_pa\":\"A\"}]}]");
        assertEquals(Set.of("A"), data.getGroups());
    }

    @Test
    public void numericPerimetreIsAccepted() {
        assertEquals(List.of("107501"), parse("[{\"code_pa\":\"A\",\"perimetre\":107501}]").getPerimetres());
    }

    @Test
    public void nullPerimetreIsIgnored() {
        PasseportAuthService.AuthData data = parse("[{\"code_pa\":\"A\",\"perimetre\":null}]");
        assertEquals(Set.of("A"), data.getGroups());
        assertTrue(data.getPerimetres().isEmpty());
    }

    @Test
    public void emptyListePaIsAccepted() {
        PasseportAuthService.AuthData data = parse("[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":[]}]");
        assertTrue(data.getGroups().isEmpty());
    }

    @Test
    public void unexpectedStructuresAreRejected() {
        String[] bodies = {
                "",                                                        // corps vide
                "not json",                                                // JSON invalide
                "{\"code_pa\":\"A\"}",                                     // objet au lieu d'un tableau
                "\"A\"",                                                   // scalaire
                "[\"A\"]",                                                 // élément scalaire
                "[null]",                                                  // élément null
                "[{}]",                                                    // ni code_pa ni listepa
                "[{\"app\":{\"code_app\":\"SY_APP\"}}]",                   // app sans listepa
                "[{\"app\":\"SY_APP\",\"listepa\":[]}]",                   // app non objet
                "[{\"app\":{\"code_app\":{}},\"listepa\":[]}]",            // code_app non textuel
                "[{\"listepa\":{\"code_pa\":\"A\"}}]",                     // listepa non tableau
                "[{\"listepa\":[\"A\"]}]",                                 // entrée de listepa scalaire
                "[{\"listepa\":[{\"perimetre\":\"1\"}]}]",                 // entrée sans code_pa
                "[{\"code_pa\":null}]",                                    // code_pa null
                "[{\"code_pa\":\"  \"}]",                                  // code_pa vide
                "[{\"code_pa\":[\"A\"]}]",                                 // code_pa non textuel
                "[{\"code_pa\":\"A\",\"perimetre\":[\"1\"]}]",             // perimetre tableau
                "[{\"code_pa\":\"A\",\"perimetre\":{\"v\":1}}]",           // perimetre objet
        };
        for (String body : bodies) {
            TrinoException e = assertThrows(TrinoException.class, () -> parse(body), "body: " + body);
            assertTrue(e.getMessage().startsWith("Unexpected response format from Passeport API"), e.getMessage());
        }
    }

    @Test
    public void errorMessagesNeverEchoTheResponseContent() {
        TrinoException e = assertThrows(TrinoException.class, () -> parse("[{\"code_pa\":\"SECRET-VALUE\",\"perimetre\":[\"x\"]}]"));
        assertTrue(!e.getMessage().contains("SECRET-VALUE"));
    }

    private static final String REAL_ARRAY = "[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":["
            + "{\"code_pa\":\"APP_RESEAU\",\"perimetre\":\"101,102,103,104,105,106\"},"
            + "{\"code_pa\":\"APP_LOC\",\"perimetre\":\"101\"},"
            + "{\"code_pa\":\"APP_NAT\",\"perimetre\":\"\"}]}]";

    /** Encode un document JSON comme une chaîne JSON, ce que fait une API qui sérialise deux fois. */
    private static String doubleEncode(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    public void realArrayIsParsed() {
        PasseportAuthService.AuthData data = PasseportResponseParser.parse(REAL_ARRAY, "SY_APP");
        assertEquals(Set.of("APP_RESEAU", "APP_LOC", "APP_NAT"), data.getGroups());
        assertEquals(List.of("101", "102", "103", "104", "105", "106"), data.getPerimetres());
    }

    @Test
    public void doubleEncodedArrayIsDecodedOnce() {
        String body = doubleEncode(REAL_ARRAY);
        assertTrue(body.startsWith("\"[{\\\"app\\\""), body);
        PasseportAuthService.AuthData data = PasseportResponseParser.parse(body, "SY_APP");
        assertEquals(Set.of("APP_RESEAU", "APP_LOC", "APP_NAT"), data.getGroups());
        assertEquals(List.of("101", "102", "103", "104", "105", "106"), data.getPerimetres());
    }

    @Test
    public void arrayWrappedInApostrophesIsParsed() {
        PasseportAuthService.AuthData data = PasseportResponseParser.parse("'" + REAL_ARRAY + "'", "SY_APP");
        assertEquals(Set.of("APP_RESEAU", "APP_LOC", "APP_NAT"), data.getGroups());
        assertEquals(List.of("101", "102", "103", "104", "105", "106"), data.getPerimetres());
    }

    @Test
    public void surroundingWhitespaceAndBomAreIgnored() {
        String[] bodies = {
                "  \n" + REAL_ARRAY + "\r\n",
                "\uFEFF" + REAL_ARRAY,
                "\uFEFF \n'" + REAL_ARRAY + "' \n",
        };
        for (String body : bodies) {
            assertEquals(Set.of("APP_RESEAU", "APP_LOC", "APP_NAT"), PasseportResponseParser.parse(body, "SY_APP").getGroups(), body);
        }
    }

    @Test
    public void apostropheWrappingIsRemovedOnlyOnce() {
        TrinoException e = assertThrows(TrinoException.class, () -> PasseportResponseParser.parse("''" + REAL_ARRAY + "''", "SY_APP"));
        assertEquals("Unexpected response format from Passeport API: the response body is not valid JSON", e.getMessage());
    }

    @Test
    public void lonelyOrMalformedApostrophesAreRejected() {
        String[] bodies = {
                "'" + REAL_ARRAY,                       // apostrophe au début seulement
                "'",                                    // apostrophe isolée
                "[{'code_pa':'A'}]",                    // apostrophes internes : pas de pseudo-JSON
                "'[{\"code_pa\":\"A\"}]\"",             // apostrophe puis guillemet
        };
        for (String body : bodies) {
            TrinoException e = assertThrows(TrinoException.class, () -> PasseportResponseParser.parse(body, "SY_APP"), body);
            assertTrue(e.getMessage().startsWith("Unexpected response format from Passeport API"), e.getMessage());
        }
    }

    @Test
    public void emptyBodyBetweenApostrophesIsRejected() {
        TrinoException e = assertThrows(TrinoException.class, () -> parse("''"));
        assertEquals("Unexpected response format from Passeport API: expected a JSON array at the top level, received MISSING", e.getMessage());
    }

    @Test
    public void objectWrappedInApostrophesIsRejectedWithItsKeys() {
        TrinoException e = assertThrows(TrinoException.class, () -> parse("'{\"data\":[],\"count\":1}'"));
        assertEquals("Unexpected response format from Passeport API: expected a JSON array at the top level, "
                + "received OBJECT (top-level keys: data, count)", e.getMessage());
    }

    @Test
    public void nullBodyIsRejected() {
        TrinoException e = assertThrows(TrinoException.class, () -> parse(null));
        assertEquals("Unexpected response format from Passeport API: the response body is not valid JSON", e.getMessage());
    }

    @Test
    public void tripleEncodedArrayIsRejected() {
        TrinoException e = assertThrows(TrinoException.class, () -> PasseportResponseParser.parse(doubleEncode(doubleEncode(REAL_ARRAY)), "SY_APP"));
        assertTrue(e.getMessage().endsWith("received STRING containing STRING"), e.getMessage());
    }

    @Test
    public void nonJsonStringIsRejectedWithItsType() {
        TrinoException e = assertThrows(TrinoException.class, () -> parse("\"SECRET-VALUE\""));
        assertEquals("Unexpected response format from Passeport API: expected a JSON array at the top level, "
                + "received STRING whose content is not valid JSON", e.getMessage());
        assertFalse(e.getMessage().contains("SECRET-VALUE"));
    }

    @Test
    public void stringContainingAnObjectIsRejectedWithItsKeys() {
        TrinoException e = assertThrows(TrinoException.class, () -> parse(doubleEncode("{\"habilitations\":[],\"statut\":\"SECRET-VALUE\"}")));
        assertEquals("Unexpected response format from Passeport API: expected a JSON array at the top level, "
                + "received STRING containing OBJECT (top-level keys: habilitations, statut)", e.getMessage());
        assertFalse(e.getMessage().contains("SECRET-VALUE"));
    }

    @Test
    public void stringContainingANumberIsRejectedWithItsType() {
        TrinoException e = assertThrows(TrinoException.class, () -> parse("\"42\""));
        assertTrue(e.getMessage().endsWith("received STRING containing NUMBER"), e.getMessage());
    }

    @Test
    public void objectIsRejectedWithItsKeys() {
        TrinoException e = assertThrows(TrinoException.class, () -> parse("{\"data\":[{\"code_pa\":\"SECRET-VALUE\"}],\"count\":1}"));
        assertEquals("Unexpected response format from Passeport API: expected a JSON array at the top level, "
                + "received OBJECT (top-level keys: data, count)", e.getMessage());
        assertFalse(e.getMessage().contains("SECRET-VALUE"));
    }

    @Test
    public void objectKeysAreSanitizedAndBounded() {
        StringBuilder body = new StringBuilder("{\"a b\\n\":1");
        for (int i = 0; i < 30; i++) {
            body.append(",\"k").append(i).append("\":1");
        }
        body.append('}');
        TrinoException e = assertThrows(TrinoException.class, () -> parse(body.toString()));
        assertTrue(e.getMessage().contains("top-level keys: a?b?, k0, k1,"), e.getMessage());
        assertTrue(e.getMessage().endsWith(", ... (31 keys))"), e.getMessage());
        assertFalse(e.getMessage().contains("\n"));
    }

    @Test
    public void scalarRootsAreRejectedWithTheirType() {
        String[][] cases = {{"42", "NUMBER"}, {"true", "BOOLEAN"}, {"null", "NULL"}};
        for (String[] c : cases) {
            TrinoException e = assertThrows(TrinoException.class, () -> parse(c[0]));
            assertTrue(e.getMessage().endsWith("received " + c[1]), e.getMessage());
        }
    }

    @Test
    public void warnsWhenAllElementsAreIgnored() {
        List<LogRecord> records = captureWarnings(() ->
                assertTrue(PasseportResponseParser.parse(REAL_ARRAY, "SY_OTHER").getGroups().isEmpty()));
        assertTrue(records.stream().anyMatch(r -> r.getMessage().startsWith("All 1 Passeport element(s) were ignored")
                && r.getMessage().contains("'SY_OTHER'") && r.getMessage().contains("no group")), records.toString());
    }

    @Test
    public void warnsWhenNoGroupIsFoundInANonEmptyArray() {
        List<LogRecord> records = captureWarnings(() ->
                assertTrue(parse("[{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":[]}]").getGroups().isEmpty()));
        assertTrue(records.stream().anyMatch(r -> r.getMessage().startsWith("The Passeport response holds 1 element(s) but no group")), records.toString());
    }

    @Test
    public void noAggregateWarningWhenGroupsAreFoundOrArrayIsEmpty() {
        List<LogRecord> records = captureWarnings(() -> {
            parse("[]");
            parse("[{\"app\":{\"code_app\":\"SY_OTHER\"},\"listepa\":[{\"code_pa\":\"X\"}]},{\"app\":{\"code_app\":\"SY_APP\"},\"listepa\":[{\"code_pa\":\"A\"}]}]");
        });
        assertTrue(records.stream().noneMatch(r -> r.getMessage().startsWith("All ") || r.getMessage().startsWith("The Passeport response holds")), records.toString());
        // l'élément d'une autre application reste signalé individuellement
        assertEquals(1, records.stream().filter(r -> r.getMessage().startsWith("Ignoring Passeport element #0")).count());
    }

    private static List<LogRecord> captureWarnings(Runnable action) {
        Logger logger = Logger.getLogger(PasseportResponseParser.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().equals(Level.WARNING)) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            action.run();
        }
        finally {
            logger.removeHandler(handler);
        }
        return records;
    }
}
