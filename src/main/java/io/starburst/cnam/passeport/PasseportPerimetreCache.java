package io.starburst.cnam.passeport;

import java.util.Collections;
import java.util.List;

public class PasseportPerimetreCache {
    
    // Singleton instance
    private static final PasseportPerimetreCache INSTANCE = new PasseportPerimetreCache();
    
    private PasseportPerimetreCache() {}

    public static PasseportPerimetreCache getInstance() {
        return INSTANCE;
    }

    public void updatePerimetre(String upn, List<String> perimetres) {
        // Obsolete (géré par PasseportAuthCache)
    }

    public List<String> getPerimetre(String upn) {
        return PasseportAuthCache.getInstance().getPerimetres(upn);
    }

    public void flush(String upn) {
        PasseportAuthCache.getInstance().invalidate(upn);
    }
    
    public void flushAll() {
        PasseportAuthCache.getInstance().invalidateAll();
    }
}
