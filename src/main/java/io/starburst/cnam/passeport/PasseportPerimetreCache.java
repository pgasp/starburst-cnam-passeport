package io.starburst.cnam.passeport;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PasseportPerimetreCache {
    
    // Singleton instance
    private static final PasseportPerimetreCache INSTANCE = new PasseportPerimetreCache();
    
    // Concurrent map to store UPN -> List of perimetres (caisses)
    private final Map<String, List<String>> userPerimetres = new ConcurrentHashMap<>();

    private PasseportPerimetreCache() {}

    public static PasseportPerimetreCache getInstance() {
        return INSTANCE;
    }

    public void updatePerimetre(String upn, List<String> perimetres) {
        if (upn != null && perimetres != null) {
            userPerimetres.put(upn, perimetres);
        }
    }

    public List<String> getPerimetre(String upn) {
        if (upn == null) {
            return Collections.emptyList();
        }
        return userPerimetres.getOrDefault(upn, Collections.emptyList());
    }

    public void flush(String upn) {
        if (upn != null) {
            userPerimetres.remove(upn);
        }
    }
    
    public void flushAll() {
        userPerimetres.clear();
    }
}
