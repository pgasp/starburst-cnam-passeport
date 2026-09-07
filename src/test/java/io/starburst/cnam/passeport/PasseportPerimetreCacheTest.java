package io.starburst.cnam.passeport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PasseportPerimetreCacheTest {

    private PasseportPerimetreCache cache;

    @BeforeEach
    public void setup() {
        cache = PasseportPerimetreCache.getInstance();
        cache.flushAll();
    }

    @Test
    public void testUpdateAndGetPerimetre() {
        String user = "agent1@cnam.fr";
        List<String> perimetres = Arrays.asList("311", "312");

        cache.updatePerimetre(user, perimetres);

        List<String> retrieved = cache.getPerimetre(user);
        assertEquals(2, retrieved.size());
        assertTrue(retrieved.contains("311"));
        assertTrue(retrieved.contains("312"));
    }

    @Test
    public void testGetPerimetreUnknownUser() {
        List<String> retrieved = cache.getPerimetre("unknown@cnam.fr");
        assertNotNull(retrieved);
        assertTrue(retrieved.isEmpty());
    }

    @Test
    public void testFlushSingleUser() {
        cache.updatePerimetre("user1", Arrays.asList("111"));
        cache.updatePerimetre("user2", Arrays.asList("222"));

        cache.flush("user1");

        assertTrue(cache.getPerimetre("user1").isEmpty());
        assertEquals(1, cache.getPerimetre("user2").size());
    }

    @Test
    public void testFlushAll() {
        cache.updatePerimetre("user1", Arrays.asList("111"));
        cache.updatePerimetre("user2", Arrays.asList("222"));

        cache.flushAll();

        assertTrue(cache.getPerimetre("user1").isEmpty());
        assertTrue(cache.getPerimetre("user2").isEmpty());
    }

    @Test
    public void testUpdateWithNullSafely() {
        cache.updatePerimetre(null, Arrays.asList("111"));
        cache.updatePerimetre("user1", null);

        assertTrue(cache.getPerimetre(null).isEmpty());
        assertTrue(cache.getPerimetre("user1").isEmpty());
    }
}
