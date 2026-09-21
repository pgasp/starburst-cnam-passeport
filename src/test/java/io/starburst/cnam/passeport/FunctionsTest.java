package io.starburst.cnam.passeport;

import io.trino.spi.type.StandardTypes;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlType;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

public class FunctionsTest {
    @Test
    public void testMethodSignatures() throws Exception {
        Method m = CryptoFunctions.class.getMethod("decryptAssmac", String.class);
        assertNotNull(m);
    }
}
