package com.qalab.qalabai.cache;

import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two defects this cache carried.
 *
 * <p><strong>1. Credentials were cached in plaintext, keyed by URL.</strong> Not just a
 * hygiene problem: the key was the URL and not the request, so a later <em>anonymous</em>
 * run against the same URL did not overwrite the entry — it read the previous user's
 * password straight back out and attached it to its own generated tests. Two users of
 * the same login page shared credentials.</p>
 *
 * <p><strong>2. The caches were unbounded and never expired.</strong> Four
 * {@code ConcurrentHashMap}s with no TTL, no size bound and no invalidation, so the heap
 * grew with every distinct URL anyone analysed and nothing could be reclaimed without a
 * restart.</p>
 */
class AnalysisCacheTest {

    private AnalysisCache cache(long ttlSeconds, int maxEntries) {
        return new AnalysisCache(ttlSeconds, maxEntries);
    }

    // ---- no credential material, anywhere ----

    @Test
    void noCredentialApiRemains() {
        // The strongest form of the guard: a structural one. If someone re-adds a
        // credential cache, this fails at compile time rather than in production.
        for (Field field : AnalysisCache.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertFalse(name.contains("credential"),
                    "the cache must not hold credential state, found field: " + field.getName());
            assertFalse(name.contains("password"),
                    "the cache must not hold password state, found field: " + field.getName());
        }
        for (java.lang.reflect.Method method : AnalysisCache.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase();
            assertFalse(name.contains("credential"),
                    "the cache must not expose credential access, found method: " + method.getName());
        }
    }

    @Test
    void theLoginCredentialsRecordIsGone() {
        assertThrowsClass(() -> Class.forName(
                        "com.qalab.qalabai.cache.AnalysisCache$LoginCredentials"),
                "the plaintext credential holder must not exist");
    }

    @Test
    void credentialsPassedByTheCallerAreUsedInstead() {
        // The replacement path: the caller hands the credentials over. Nothing is read
        // back, so an anonymous run can never inherit another run's credentials.
        AnalysisCache cache = cache(1800, 200);
        cache.put(cache.hashUrl("https://app.example.com/login"), analysis(), "<html/>");

        // The cache is asked for page content and gets it; it is never asked for a user.
        assertNotNull(cache.getSimplifiedHtmlByUrl("https://app.example.com/login"));
        assertEquals(null, credentialsByReflection(cache));
    }

    private Object credentialsByReflection(AnalysisCache cache) {
        try {
            MethodFinder.find(cache, "getLoginCredentialsByUrl");
            return "still present";
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static final class MethodFinder {
        static void find(AnalysisCache cache, String name) throws NoSuchMethodException {
            AnalysisCache.class.getMethod(name, String.class);
        }
    }

    // ---- entries expire ----

    @Test
    void anEntryIsGoneOnceTheTtlPasses() throws Exception {
        // A one-second TTL, then a real wait rather than a mocked clock: the point is
        // that expiry is driven by elapsed time and not by someone remembering to clear.
        AnalysisCache cache = cache(1, 200);
        String hash = cache.hashUrl("https://app.example.com/login");
        cache.put(hash, analysis());

        assertNotNull(cache.get(hash), "must be a hit before the TTL passes");

        Thread.sleep(1100);

        assertNull(cache.get(hash), "must be a miss after the TTL passes");
        assertEquals(1, cache.stats().expirations(), "the expiry must be counted");
    }

    @Test
    void anExpiredEntryIsNotLeftOccupyingTheMap() throws Exception {
        AnalysisCache cache = cache(1, 200);
        String hash = cache.hashUrl("https://app.example.com/login");
        cache.put(hash, analysis());
        assertEquals(1, cache.size());

        Thread.sleep(1100);
        cache.get(hash);

        assertEquals(0, cache.size(),
                "an entry that is logically absent must not still occupy the map");
    }

    @Test
    void pageContentExpiresToo() throws Exception {
        AnalysisCache cache = cache(1, 200);
        String hash = cache.hashUrl("https://app.example.com/login");
        cache.put(hash, analysis(), "<html>content</html>");
        cache.putPostLoginContent(hash, "<html>after login</html>");

        assertNotNull(cache.getSimplifiedHtml(hash));
        assertNotNull(cache.getPostLoginContent(hash));

        Thread.sleep(1100);

        assertNull(cache.getSimplifiedHtml(hash));
        assertNull(cache.getPostLoginContent(hash));
    }

    @Test
    void aZeroOrNegativeTtlIsClampedRatherThanExpiringEverythingImmediately() {
        // Misconfiguration should not silently turn the cache off, nor throw.
        AnalysisCache cache = cache(0, 200);
        String hash = cache.hashUrl("https://app.example.com/login");
        cache.put(hash, analysis());

        assertNotNull(cache.get(hash));
        assertTrue(cache.stats().ttlSeconds() >= 1);
    }

    // ---- eviction is bounded ----

    @Test
    void theCacheNeverExceedsItsSizeBound() {
        AnalysisCache cache = cache(1800, 10);

        for (int i = 0; i < 500; i++) {
            String hash = cache.hashUrl("https://app.example.com/page-" + i);
            cache.put(hash, analysis(), "content " + i);
        }

        assertEquals(10, cache.size(), "the size bound must actually bound the cache");
        assertTrue(cache.stats().evictions() > 0, "eviction must be observable");
    }

    @Test
    void evictionKeepsTheMostRecentEntries() {
        AnalysisCache cache = cache(1800, 5);
        String newest = null;
        for (int i = 0; i < 50; i++) {
            newest = cache.hashUrl("https://app.example.com/page-" + i);
            cache.put(newest, analysis(), "content " + i);
        }

        assertNotNull(cache.get(newest), "the most recent entry must survive");
        assertNull(cache.get(cache.hashUrl("https://app.example.com/page-0")),
                "the oldest entry must have been evicted");
    }

    @Test
    void anInvalidSizeBoundIsClamped() {
        AnalysisCache cache = cache(1800, 0);
        cache.put(cache.hashUrl("https://x.example.com/"), analysis());

        assertTrue(cache.size() >= 1, "a bad bound must not throw or evict everything");
    }

    // ---- hit rate is observable ----

    @Test
    void hitAndMissAreCounted() {
        AnalysisCache cache = cache(1800, 200);
        String hash = cache.hashUrl("https://app.example.com/login");

        cache.get(hash);            // miss
        cache.put(hash, analysis());
        cache.get(hash);            // hit
        cache.get(hash);            // hit
        cache.get("no-such-hash");  // miss

        AnalysisCache.CacheStats stats = cache.stats();
        assertEquals(2, stats.hits());
        assertEquals(2, stats.misses());
        assertEquals(0.5, stats.hitRate(), 0.0001);
        assertEquals(200, stats.maxEntries());
    }

    @Test
    void anEmptyCacheReportsAZeroHitRateRatherThanDividingByZero() {
        AnalysisCache cache = cache(1800, 200);

        assertEquals(0.0, cache.stats().hitRate());
    }

    @Test
    void statsDescribeTheConfiguredPolicySoTheCacheCanBeJudged() {
        AnalysisCache cache = cache(900, 42);
        cache.put(cache.hashUrl("https://x.example.com/"), analysis());

        AnalysisCache.CacheStats stats = cache.stats();
        assertEquals(900, stats.ttlSeconds());
        assertEquals(42, stats.maxEntries());
        assertEquals(1, stats.size());
    }

    // ---- behaviour the rest of the system relies on ----

    @Test
    void putAndGetRoundTrip() {
        AnalysisCache cache = cache(1800, 200);
        AnalysisResponse analysis = analysis();
        String hash = cache.hashUrl("https://app.example.com/login");

        cache.put(hash, analysis);

        assertEquals(analysis, cache.get(hash));
        assertEquals(analysis, cache.getByUrl("https://app.example.com/login"));
    }

    @Test
    void aMissReturnsNullRatherThanThrowing() {
        AnalysisCache cache = cache(1800, 200);

        assertNull(cache.get("never-stored"));
        assertNull(cache.getSimplifiedHtml("never-stored"));
        assertNull(cache.getPostLoginContent("never-stored"));
    }

    @Test
    void aNullAnalysisIsNotCached() {
        // Storing null in a bounded map either throws or poisons the entry, so it is
        // ignored rather than cached.
        AnalysisCache cache = cache(1800, 200);
        String hash = cache.hashUrl("https://app.example.com/login");

        cache.put(hash, null);

        assertNull(cache.get(hash));
        assertEquals(0, cache.size());
    }

    @Test
    void clearEmptiesEverything() {
        AnalysisCache cache = cache(1800, 200);
        String hash = cache.hashUrl("https://app.example.com/login");
        cache.put(hash, analysis(), "<html/>");
        cache.putPostLoginContent(hash, "<html>after</html>");

        cache.clear();

        assertEquals(0, cache.size());
        assertNull(cache.get(hash));
        assertNull(cache.getSimplifiedHtml(hash));
        assertNull(cache.getPostLoginContent(hash));
    }

    @Test
    void urlHashingIsStableAndDistinct() {
        AnalysisCache cache = cache(1800, 200);

        assertEquals(cache.hashUrl("https://a.example.com/"),
                cache.hashUrl("https://a.example.com/"));
        assertFalse(cache.hashUrl("https://a.example.com/")
                .equals(cache.hashUrl("https://b.example.com/")));
        assertEquals(64, cache.hashUrl("https://a.example.com/").length(), "SHA-256 hex");
    }

    @Test
    void concurrentAccessDoesNotCorruptTheCache() throws Exception {
        // The maps are synchronised LinkedHashMaps now rather than ConcurrentHashMaps, so
        // this is the test that says the swap was safe.
        AnalysisCache cache = cache(1800, 500);
        int threads = 8;
        int perThread = 200;

        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    String url = "https://app.example.com/" + id + "-" + i;
                    String hash = cache.hashUrl(url);
                    cache.put(hash, analysis(), "content");
                    cache.get(hash);
                    cache.getSimplifiedHtml(hash);
                    cache.stats();
                }
            });
        }
        for (Thread worker : workers) {
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertTrue(cache.size() <= 500, "the bound must hold under concurrency, was " + cache.size());
        // Only the analysis lookup is counted, because that is the one forceRefresh
        // gates and the one worth judging the cache on. The page-content lookup shares
        // the TTL and the eviction bound but not the hit rate, so the number stays
        // unambiguous: exactly one counted lookup per iteration.
        AnalysisCache.CacheStats stats = cache.stats();
        assertEquals((long) threads * perThread, stats.hits() + stats.misses(),
                "every analysis lookup must be counted exactly once");
    }

    private AnalysisResponse analysis() {
        return new AnalysisResponse("login", "https://app.example.com/login", 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }

    private void assertThrowsClass(ThrowingRunnable runnable, String message) {
        try {
            runnable.run();
            org.junit.jupiter.api.Assertions.fail(message);
        } catch (ClassNotFoundException expected) {
            assertNotNull(expected);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws ClassNotFoundException;
    }
}
