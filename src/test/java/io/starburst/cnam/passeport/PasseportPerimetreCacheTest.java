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

        PasseportAuthCache.getInstance().put(user, Collections.emptySet(), perimetres);

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
        PasseportAuthCache.getInstance().put("user1", Collections.emptySet(), Arrays.asList("111"));
        PasseportAuthCache.getInstance().put("user2", Collections.emptySet(), Arrays.asList("222"));

        cache.flush("user1");

        assertTrue(cache.getPerimetre("user1").isEmpty());
        assertEquals(1, cache.getPerimetre("user2").size());
    }

    @Test
    public void testFlushAll() {
        PasseportAuthCache.getInstance().put("user1", Collections.emptySet(), Arrays.asList("111"));
        PasseportAuthCache.getInstance().put("user2", Collections.emptySet(), Arrays.asList("222"));

        cache.flushAll();

        assertTrue(cache.getPerimetre("user1").isEmpty());
        assertTrue(cache.getPerimetre("user2").isEmpty());
    }

    @Test
    public void testUpdateWithNullSafely() {
        PasseportAuthCache.getInstance().put(null, Collections.emptySet(), Arrays.asList("111"));
        PasseportAuthCache.getInstance().put("user1", Collections.emptySet(), null);

        assertTrue(cache.getPerimetre(null).isEmpty());
        assertTrue(cache.getPerimetre("user1").isEmpty());
    }
}
