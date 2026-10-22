package io.starburst.cnam.passeport;

import io.trino.spi.TrinoException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
