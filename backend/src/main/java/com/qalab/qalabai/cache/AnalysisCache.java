package com.qalab.qalabai.cache;

import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Short-lived, bounded cache of page analyses.
 *
 * <p>This used to be four unbounded {@link java.util.concurrent.ConcurrentHashMap}s
 * with no TTL, no size bound and no invalidation, and one of them stored
 * <strong>plaintext usernames and passwords</strong> keyed by a hash of the URL. Two
 * problems, in increasing order of seriousness:</p>
 *
 * <ol>
 *   <li>Unbounded and permanent: entries lived for the process lifetime, so the heap
 *       grew with every distinct URL anyone analysed and nothing could be reclaimed
 *       short of a restart.</li>
 *   <li><strong>Cross-request credential leak.</strong> The key was the URL, not the
 *       request. {@code ExplorerService} only writes credentials after a successful
 *       login, so a later <em>anonymous</em> run against the same URL did not
 *       overwrite the entry — and {@code CodeGenerationService} read the earlier user's
 *       password straight back out and attached it to the new run's generated tests.
 *       Two different users of the same login page would share credentials.</li>
 * </ol>
 *
 * <p>Credential caching is therefore <strong>removed rather than bounded</strong>. A
 * TTL would shrink the window without closing it, and there is nothing to gain from it:
 * the caller already holds the credentials for the request in hand and now passes them
 * through directly. A URL-keyed plaintext password in a long-lived heap is a liability
 * with no benefit.</p>
 *
 * <p>What remains is cached page analysis — expensive to recompute, containing nothing
 * sensitive — with a TTL, a size bound, and hit/miss counters so "always re-analyse"
 * becomes a decision with a number attached rather than a default.</p>
 */
@Component
public class AnalysisCache {

    private static final Logger log = LoggerFactory.getLogger(AnalysisCache.class);

    private final Duration ttl;
    private final int maxEntries;

    /**
     * Insertion-ordered, so the eldest key is the first key and eviction is a single
     * {@code removeEldestEntry} rather than a scan.
     *
     * <p>Every method that touches these maps is {@code synchronized}, and that is load-
     * bearing rather than decorative. {@code removeEldestEntry} reads {@code size()}
     * during insertion, so concurrent writers each observe a smaller map and all of them
     * decide nothing needs evicting — the bound is then exceeded. Uncoordinated
     * mutation of a {@link LinkedHashMap} can also corrupt its own structure. A
     * concurrency test caught exactly that: 525 entries in a cache bounded to 500.</p>
     *
     * <p>A cache lookup is not the bottleneck — the browser navigation and LLM call that
     * follow it are orders of magnitude slower — so the lock costs nothing real.</p>
     */
    private final Map<String, Entry<AnalysisResponse>> analyses;
    private final Map<String, Entry<String>> pageContent;
    private final Map<String, Entry<String>> postLoginContent;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong expirations = new AtomicLong();

    public AnalysisCache(
            @Value("${qalab.cache.analysis-ttl-seconds:1800}") long ttlSeconds,
            @Value("${qalab.cache.max-entries:200}") int maxEntries) {
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.maxEntries = Math.max(1, maxEntries);
        this.analyses = boundedAnalyses();
        this.pageContent = boundedText();
        this.postLoginContent = boundedText();
        log.info("Analysis cache: ttl={}s, maxEntries={}", this.ttl.toSeconds(), this.maxEntries);
    }

    private Map<String, Entry<AnalysisResponse>> boundedAnalyses() {
        return new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry<AnalysisResponse>> eldest) {
                boolean evict = size() > AnalysisCache.this.maxEntries;
                if (evict) {
                    evictions.incrementAndGet();
                }
                return evict;
            }
        };
    }

    private Map<String, Entry<String>> boundedText() {
        return new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry<String>> eldest) {
                boolean evict = size() > AnalysisCache.this.maxEntries;
                if (evict) {
                    evictions.incrementAndGet();
                }
                return evict;
            }
        };
    }

    private record Entry<T>(T value, Instant storedAt) {
        boolean isFresh(Duration ttl) {
            return Duration.between(storedAt, Instant.now()).compareTo(ttl) < 0;
        }
    }

    // ---- analysis ----

    public synchronized AnalysisResponse get(String urlHash) {
        Entry<AnalysisResponse> entry = analyses.get(urlHash);
        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }
        if (!entry.isFresh(ttl)) {
            // Removed on read rather than left to a sweep: the alternative is an entry
            // that is logically absent but still occupies the map.
            analyses.remove(urlHash);
            expirations.incrementAndGet();
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        log.info("Cache hit for URL hash: {}", urlHash);
        return entry.value();
    }

    public synchronized void put(String urlHash, AnalysisResponse response) {
        put(urlHash, response, null);
    }

    public synchronized void put(String urlHash, AnalysisResponse response, String simplifiedHtml) {
        if (response == null) {
            return;
        }
        analyses.put(urlHash, new Entry<>(response, Instant.now()));
        if (simplifiedHtml != null) {
            pageContent.put(urlHash, new Entry<>(simplifiedHtml, Instant.now()));
        }
        log.info("Cached analysis for URL hash: {}", urlHash);
    }

    // ---- page content ----

    public synchronized String getSimplifiedHtml(String urlHash) {
        return fresh(pageContent, urlHash);
    }

    public synchronized void putPostLoginContent(String urlHash, String simplifiedHtml) {
        if (simplifiedHtml != null) {
            postLoginContent.put(urlHash, new Entry<>(simplifiedHtml, Instant.now()));
        }
    }

    public synchronized String getPostLoginContent(String urlHash) {
        return fresh(postLoginContent, urlHash);
    }

    private synchronized <T> T fresh(Map<String, Entry<T>> map, String key) {
        Entry<T> entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (!entry.isFresh(ttl)) {
            map.remove(key);
            expirations.incrementAndGet();
            return null;
        }
        return entry.value();
    }

    public synchronized void clear() {
        analyses.clear();
        pageContent.clear();
        postLoginContent.clear();
        log.info("Analysis cache cleared");
    }

    // ---- URL conveniences ----

    public String hashUrl(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public AnalysisResponse getByUrl(String url) {
        return get(hashUrl(url));
    }

    public String getSimplifiedHtmlByUrl(String url) {
        return getSimplifiedHtml(hashUrl(url));
    }

    public String getPostLoginContentByUrl(String url) {
        return getPostLoginContent(hashUrl(url));
    }

    public synchronized int size() {
        return analyses.size();
    }

    // ---- observability ----

    /**
     * Hit/miss counters, so "should the cache stay?" is answerable from a number.
     * Without them the only way to judge a cache is to delete it and see whether
     * anything gets slower.
     */
    public record CacheStats(long hits, long misses, long evictions, long expirations,
                             int size, long ttlSeconds, int maxEntries) {

        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }

    public synchronized CacheStats stats() {
        return new CacheStats(hits.get(), misses.get(), evictions.get(), expirations.get(),
                analyses.size(), ttl.toSeconds(), maxEntries);
    }
}
