package io.starburst.cnam.passeport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.airlift.slice.Slices;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

public class HashFunctionsTest {

    // Valeur de référence calculée hors du code : echo -n 'value1|user.test' | openssl dgst -sha256 (equivalent to to_hex(sha256(to_utf8('value1' || '|' || 'user.test'))) in SQL)
    private static final String CLUSTER_REFERENCE = "F338F2941AD7FC4FC1CD8ABB256058C7861503C65D6D180C66BF6FB303993493";

    private static String call(String value, String salt) {
        return HashFunctions.hashUserSalt(Slices.utf8Slice(value), Slices.utf8Slice(salt)).toStringUtf8();
    }

    @Test
    public void matchesTrinoSqlExpression() {
        assertEquals(CLUSTER_REFERENCE, call("value1", "user.test"));
    }

    @Test
    public void matchesJdkReferenceForVariedInputs() throws Exception {
        String[][] cases = {{"", ""}, {"a", "b"}, {"VALUE_42", "user.one"}, {"accentué", "émilie.test"}};
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
        String first = call("value1", "user.test");
        for (int i = 0; i < 1000; i++) {
            assertEquals(first, call("value1", "user.test"));
        }
    }
}
