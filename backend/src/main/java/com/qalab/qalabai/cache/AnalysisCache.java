package com.qalab.qalabai.cache;

import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AnalysisCache {

    private static final Logger log = LoggerFactory.getLogger(AnalysisCache.class);

    private final Map<String, AnalysisResponse> cache = new ConcurrentHashMap<>();
    private final Map<String, String> pageContentCache = new ConcurrentHashMap<>();
    private final Map<String, String> postLoginContentCache = new ConcurrentHashMap<>();
    private final Map<String, LoginCredentials> loginCredentialsCache = new ConcurrentHashMap<>();

    public record LoginCredentials(String username, String password) {}

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
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public AnalysisResponse get(String urlHash) {
        AnalysisResponse result = cache.get(urlHash);
        if (result != null) {
            log.info("Cache hit for URL hash: {}", urlHash);
        }
        return result;
    }

    public void put(String urlHash, AnalysisResponse response) {
        cache.put(urlHash, response);
        log.info("Cached analysis for URL hash: {}", urlHash);
    }

    public void put(String urlHash, AnalysisResponse response, String simplifiedHtml) {
        cache.put(urlHash, response);
        if (simplifiedHtml != null) {
            pageContentCache.put(urlHash, simplifiedHtml);
        }
        log.info("Cached analysis for URL hash: {}", urlHash);
    }

    public String getSimplifiedHtml(String urlHash) {
        return pageContentCache.get(urlHash);
    }

    public void putPostLoginContent(String urlHash, String simplifiedHtml) {
        if (simplifiedHtml != null) {
            postLoginContentCache.put(urlHash, simplifiedHtml);
        }
    }

    public String getPostLoginContent(String urlHash) {
        return postLoginContentCache.get(urlHash);
    }

    public void putLoginCredentials(String urlHash, String username, String password) {
        if (username != null && !username.isBlank()) {
            loginCredentialsCache.put(urlHash, new LoginCredentials(username, password));
        }
    }

    public LoginCredentials getLoginCredentials(String urlHash) {
        return loginCredentialsCache.get(urlHash);
    }

    public void clear() {
        cache.clear();
        pageContentCache.clear();
        postLoginContentCache.clear();
        loginCredentialsCache.clear();
        log.info("Analysis cache cleared");
    }

    public AnalysisResponse getByUrl(String url) {
        String hash = hashUrl(url);
        return get(hash);
    }

    public String getSimplifiedHtmlByUrl(String url) {
        String hash = hashUrl(url);
        return getSimplifiedHtml(hash);
    }

    public String getPostLoginContentByUrl(String url) {
        String hash = hashUrl(url);
        return getPostLoginContent(hash);
    }

    public LoginCredentials getLoginCredentialsByUrl(String url) {
        String hash = hashUrl(url);
        return getLoginCredentials(hash);
    }

    public int size() {
        return cache.size();
    }
}
