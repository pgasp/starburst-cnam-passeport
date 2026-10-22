package io.starburst.cnam.passeport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.StandardErrorCode;
import io.trino.spi.TrinoException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class PseudonymizeFunctionsTest {

    // CLES DE TEST UNIQUEMENT (valeurs arbitraires, sans lien avec une clé déployée).
    private static final String TEST_KEY_A = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
    private static final String TEST_KEY_B = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";

    private static UnaryOperator<String> env(String key)
    {
        Map<String, String> map = key == null ? Map.of() : Map.of(Pseudonymizer.KEY_ENV_VARIABLE, key);
        return map::get;
    }

    private static String call(String value)
    {
        Slice result = PseudonymizeFunctions.pseudonymize(Slices.utf8Slice(value));
        return result == null ? null : result.toStringUtf8();
    }

    @AfterEach
    public void restoreEnvironment()
    {
        PseudonymizeFunctions.useEnvironment(System::getenv);
    }

    // --- Validation de HMAC-SHA256 : vecteurs de test RFC 4231 (section 4) ---

    private static byte[] repeat(int value, int count)
    {
        byte[] bytes = new byte[count];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static void assertRfc4231(byte[] key, byte[] data, String expectedHex)
    {
        assertEquals(expectedHex, new Pseudonymizer(key).hmacHex(data).toLowerCase());
    }

    @Test
    public void rfc4231TestCase1()
    {
        assertRfc4231(repeat(0x0b, 20), "Hi There".getBytes(StandardCharsets.US_ASCII),
                "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
    }

    @Test
    public void rfc4231TestCase2()
    {
        assertRfc4231("Jefe".getBytes(StandardCharsets.US_ASCII), "what do ya want for nothing?".getBytes(StandardCharsets.US_ASCII),
                "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    public void rfc4231TestCase3()
    {
        assertRfc4231(repeat(0xaa, 20), repeat(0xdd, 50),
                "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe");
    }

    @Test
    public void rfc4231TestCase4()
    {
        assertRfc4231(HexFormat.of().parseHex("0102030405060708090a0b0c0d0e0f10111213141516171819"), repeat(0xcd, 50),
                "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b");
    }

    @Test
    public void rfc4231TestCase6KeyLargerThanBlockSize()
    {
        assertRfc4231(repeat(0xaa, 131), "Test Using Larger Than Block-Size Key - Hash Key First".getBytes(StandardCharsets.US_ASCII),
                "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54");
    }

    @Test
    public void rfc4231TestCase7KeyAndDataLargerThanBlockSize()
    {
        assertRfc4231(repeat(0xaa, 131),
                ("This is a test using a larger than block-size key and a larger than block-size data. "
                        + "The key needs to be hashed before being used by the HMAC algorithm.").getBytes(StandardCharsets.US_ASCII),
                "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2");
    }

    // --- Comportement de l'UDF ---

    @Test
    public void udfMatchesHmacOfNormalizedValue()
    {
        PseudonymizeFunctions.useEnvironment(env(TEST_KEY_A));
        String expected = new Pseudonymizer(HexFormat.of().parseHex(TEST_KEY_A))
                .hmacHex("123456789".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, call("123456789"));
        assertEquals(64, call("123456789").length());
        assertTrue(call("123456789").matches("[0-9A-F]{64}"));
    }

    @Test
    public void isDeterministicAcrossCallsAndInstances()
    {
        PseudonymizeFunctions.useEnvironment(env(TEST_KEY_A));
        String first = call("REF-000123");
        for (int i = 0; i < 1000; i++) {
            assertEquals(first, call("REF-000123"));
        }
        // Simule un redémarrage / un autre worker : nouvelle lecture de la même clé.
        PseudonymizeFunctions.useEnvironment(env(TEST_KEY_A));
        assertEquals(first, call("REF-000123"));
    }

    @Test
    public void differentValuesGiveDifferentPseudonyms()
    {
        PseudonymizeFunctions.useEnvironment(env(TEST_KEY_A));
        assertNotEquals(call("REF-000123"), call("REF-000124"));
    }

    @Test
    public void normalizationTrimsAndUppercases()
    {
        PseudonymizeFunctions.useEnvironment(env(TEST_KEY_A));
        String reference = call("AB12");
        assertEquals(reference, call("ab12"));
        assertEquals(reference, call("  ab12\t"));
        assertEquals(reference, call("\n Ab12 "));
        // Les espaces internes restent significatifs.
        assertNotEquals(reference, call("AB 12"));
    }

    @Test
    public void blankValueGivesNullInsteadOfAConstantPseudonym()
    {
        PseudonymizeFunctions.useEnvironment(env(TEST_KEY_A));
        assertNull(call(""));
        assertNull(call("   "));
    }

    @Test
    public void twoDifferentKeysGiveDifferentPseudonyms()
    {
        PseudonymizeFunctions.useEnvironment(env(TEST_KEY_A));
        String withKeyA = call("REF-000123");
        PseudonymizeFunctions.useEnvironment(env(TEST_KEY_B));
        String withKeyB = call("REF-000123");
        assertNotEquals(withKeyA, withKeyB);
    }

    @Test
    public void nullInputIsHandledByTheEngineAndDeclaredNullable() throws Exception
    {
        // Trino renvoie NULL pour une entrée NULL sans appeler la méthode (argument non @SqlNullable) ;
        // la méthode, elle, peut renvoyer NULL (valeur vide) : le retour doit donc être @SqlNullable.
        var method = PseudonymizeFunctions.class.getMethod("pseudonymize", Slice.class);
        assertNotNull(method.getAnnotation(io.trino.spi.function.SqlNullable.class));
        assertFalse(Arrays.stream(method.getParameterAnnotations()[0])
                .anyMatch(a -> a instanceof io.trino.spi.function.SqlNullable));
    }

    @Test
    public void missingKeyFailsClosedWithExplicitError()
    {
        PseudonymizeFunctions.useEnvironment(env(null));
        TrinoException e = assertThrows(TrinoException.class, () -> call("REF-000123"));
        assertEquals(StandardErrorCode.CONFIGURATION_INVALID.toErrorCode(), e.getErrorCode());
        assertTrue(e.getMessage().contains(Pseudonymizer.KEY_ENV_VARIABLE));
    }

    @Test
    public void emptyKeyFailsClosed()
    {
        PseudonymizeFunctions.useEnvironment(env("   "));
        assertThrows(TrinoException.class, () -> call("x"));
    }

    @Test
    public void invalidOrTooShortKeysFailClosedWithoutLeakingTheValue()
    {
        for (String bad : List.of("not-hex-not-hex-not-hex-not-hex-not-hex-not-hex-not-hex-1234", "abc", TEST_KEY_A.substring(0, 62), TEST_KEY_A + "0")) {
            PseudonymizeFunctions.useEnvironment(env(bad));
            TrinoException e = assertThrows(TrinoException.class, () -> call("x"));
            assertFalse(e.getMessage().contains(bad), "the error message must not contain the key value");
            assertNull(e.getCause());
        }
    }

    @Test
    public void keyIsReadAgainAfterAFailure()
    {
        PseudonymizeFunctions.useEnvironment(env(null));
        assertThrows(TrinoException.class, () -> call("x"));
        // L'échec n'est pas mis en cache : la clé devient disponible, l'UDF fonctionne.
        PseudonymizeFunctions.useEnvironment(env(TEST_KEY_A));
        assertNotNull(call("x"));
    }

    @Test
    public void toStringDoesNotExposeTheKey()
    {
        Pseudonymizer p = Pseudonymizer.fromEnvironment(env(TEST_KEY_A));
        assertFalse(p.toString().toLowerCase().contains(TEST_KEY_A.substring(0, 16)));
    }

    @Test
    public void functionIsRegisteredByThePlugin()
    {
        Set<Class<?>> functions = new PasseportPlugin().getFunctions();
        assertTrue(functions.contains(PseudonymizeFunctions.class));
    }

    @Test
    public void isThreadSafe() throws Exception
    {
        PseudonymizeFunctions.useEnvironment(env(TEST_KEY_A));
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            inputs.add("MATRICULE-" + i);
        }
        List<String> expected = inputs.stream().map(PseudonymizeFunctionsTest::call).toList();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                Callable<List<String>> task = () -> {
                    List<String> out = new ArrayList<>();
                    for (int round = 0; round < 50; round++) {
                        out.clear();
                        for (String input : inputs) {
                            out.add(call(input));
                        }
                    }
                    return out;
                };
                futures.add(pool.submit(task));
            }
            for (Future<List<String>> future : futures) {
                assertEquals(expected, future.get());
            }
        }
        finally {
            pool.shutdownNow();
        }
    }
}
