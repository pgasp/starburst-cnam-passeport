package io.starburst.cnam.passeport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AssmacCipherTest {

    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private byte[] key;
    private SecureRandom secureRandom;

    @BeforeEach
    public void setup() {
        secureRandom = new SecureRandom();
        key = new byte[32];
        secureRandom.nextBytes(key);
        AssmacCipher.setKey(key);
    }

    @Test
    public void testEncryptThenDecryptRoundTrip() throws Exception {
        String plaintext = "1234567890ABCDEF";

        String wireValue = encryptForTest(plaintext, key);

        String decrypted = AssmacCipher.decrypt(wireValue);

        assertEquals(plaintext, decrypted, "Decrypted value must match the original plaintext");
    }

    @Test
    public void testDecryptWithCorruptedInputThrows() {
        // Truncated / invalid base64 input must propagate an exception (fail-open at this layer;
        // the scalar function caller is responsible for fail-closed handling).
        String corrupted = "not-valid-base64-!!!";

        assertThrows(RuntimeException.class, () -> AssmacCipher.decrypt(corrupted));
    }

    /**
     * Builds a wire-format value (base64(IV || ciphertext+tag)) exactly matching the contract,
     * independently of AssmacCipher, to validate the Java-side implementation end-to-end.
     */
    private static String encryptForTest(String plaintext, byte[] key) throws Exception {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
        byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] wire = new byte[iv.length + ciphertextWithTag.length];
        System.arraycopy(iv, 0, wire, 0, iv.length);
        System.arraycopy(ciphertextWithTag, 0, wire, iv.length, ciphertextWithTag.length);

        return Base64.getEncoder().encodeToString(wire);
    }
}
