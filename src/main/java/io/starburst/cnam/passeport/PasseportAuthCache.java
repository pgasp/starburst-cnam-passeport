package io.starburst.cnam.passeport;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class PasseportAuthCache {

    private static final PasseportAuthCache INSTANCE = new PasseportAuthCache();

    public static class AuthData {
        private final Set<String> groups;
        private final List<String> perimetres;

        public AuthData(Set<String> groups, List<String> perimetres) {
            this.groups = groups;
            this.perimetres = perimetres;
        }

        public Set<String> getGroups() {
            return groups;
        }

        public List<String> getPerimetres() {
            return perimetres;
        }
    }

    // Guava Cache avec TTL de 15 minutes
    private final Cache<String, AuthData> cache;

    private PasseportAuthCache() {
        this.cache = CacheBuilder.newBuilder()
                .expireAfterWrite(15, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
    }

    public static PasseportAuthCache getInstance() {
        return INSTANCE;
    }

    public void put(String upn, Set<String> groups, List<String> perimetres) {
        if (upn != null) {
            cache.put(upn, new AuthData(groups != null ? groups : Collections.emptySet(), 
                                        perimetres != null ? perimetres : Collections.emptyList()));
        }
    }

    public AuthData get(String upn) {
        if (upn == null) {
            return null;
        }
        return cache.getIfPresent(upn);
    }

    public List<String> getPerimetres(String upn) {
        AuthData data = get(upn);
        return data != null ? data.getPerimetres() : Collections.emptyList();
    }

    public void invalidate(String upn) {
        if (upn != null) {
            cache.invalidate(upn);
        }
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}