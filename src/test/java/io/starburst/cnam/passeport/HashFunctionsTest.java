package io.starburst.cnam.passeport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.airlift.slice.Slices;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

public class HashFunctionsTest {

    // Valeur calculée par le cluster : SELECT to_hex(sha256(to_utf8('benidf1' || '|' || 'pascal.gasp')))
    private static final String CLUSTER_REFERENCE = "659EF9D3BB03D5ECBF3BCB869F9B190464958942A384637ECEDFDE07ABC673F6";

    private static String call(String value, String salt) {
        return HashFunctions.hashUserSalt(Slices.utf8Slice(value), Slices.utf8Slice(salt)).toStringUtf8();
    }

    @Test
    public void matchesTrinoSqlExpression() {
        assertEquals(CLUSTER_REFERENCE, call("benidf1", "pascal.gasp"));
    }

    @Test
    public void matchesJdkReferenceForVariedInputs() throws Exception {
        String[][] cases = {{"", ""}, {"a", "b"}, {"BEN_42", "kader.kassed"}, {"accentué", "émilie.letullier"}};
        for (String[] c : cases) {
            byte[] expected = MessageDigest.getInstance("SHA-256").digest((c[0] + "|" + c[1]).getBytes(StandardCharsets.UTF_8));
            assertEquals(HexFormat.of().withUpperCase().formatHex(expected), call(c[0], c[1]));
        }
    }

    @Test
    public void saltChangesTheDigest() {
        assertEquals(64, call("x", "u1").length());
        assertEquals(false, call("x", "u1").equals(call("x", "u2")));
    }

    @Test
    public void repeatedCallsAreStable() {
        String first = call("benidf1", "pascal.gasp");
        for (int i = 0; i < 1000; i++) {
            assertEquals(first, call("benidf1", "pascal.gasp"));
        }
    }
}
