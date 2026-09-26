package com.qalab.qalabai.security;

import com.qalab.qalabai.api.ErrorCode;
import com.qalab.qalabai.service.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end HTTP verification of B-013.
 *
 * <p>This is the check that matters most: a security control verified only at the unit
 * level can pass while the deployed application serves everything to anyone. It boots
 * the real application with {@code require-api-key=true} and exercises the actual HTTP
 * surface, including the error body shape the CLI parses.</p>
 *
 * <p>It also pins the startup-ordering fix: the filter is constructed while Tomcat
 * starts, before the JPA EntityManagerFactory exists, so it must hold its
 * dependencies as {@code ObjectProvider}. Injecting the repository directly fails the
 * whole context.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "qalab.security.require-api-key=true",
                "qalab.auto-install-playwright=false",
                "qalab.auto-start-frontend=false",
                "qalab.auto-open-browser=false"
        })
@ActiveProfiles("test")
class ApiKeyHttpIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApiKeyService apiKeyService;

    private String issueKey() {
        return apiKeyService.issue(null, "integration").rawKey();
    }

    private HttpHeaders auth(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(key);
        return headers;
    }

    @Test
    void theContextLoadsWithSecurityEnabled() {
        // If the filter's dependencies were resolved eagerly this test would not reach
        // the assertions at all: the context would fail to start.
        assertNotNull(rest, "the application must boot with API-key security enabled");
    }

    @Test
    void theBootstrapProbeIsPublicAndSaysAKeyIsRequired() {
        ResponseEntity<Map> res = rest.getForEntity("/api/v1/account/bootstrap", Map.class);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(Boolean.TRUE, res.getBody().get("apiKeyRequired"));
        assertEquals("Bearer", res.getBody().get("headerScheme"));
    }

    /** A real v1 endpoint that exists; used as the canary for "is auth enforced?". */
    private static final String PROTECTED = "/api/v1/account/budget-policy";

    @Test
    void anUnauthenticatedCallToTheApiIsRejected() {
        ResponseEntity<String> res = rest.getForEntity("/api/v1/account/budget-policy", String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        assertTrue(res.getBody().contains(ErrorCode.UNAUTHENTICATED),
                "must use the shared error envelope the CLI parses: " + res.getBody());
        assertTrue(res.getBody().contains("Authorization: Bearer"),
                "the message should say how to authenticate: " + res.getBody());
    }

    @Test
    void anInvalidKeyIsRejected() {
        ResponseEntity<String> res = rest.exchange("/api/v1/account/budget-policy", HttpMethod.GET,
                new HttpEntity<>(auth("qalab_definitely_not_a_real_key")), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
    }

    @Test
    void aValidKeyIsAccepted() {
        ResponseEntity<String> res = rest.exchange("/api/v1/account/budget-policy", HttpMethod.GET,
                new HttpEntity<>(auth(issueKey())), String.class);

        assertEquals(HttpStatus.OK, res.getStatusCode(),
                "a valid key must be accepted: " + res.getBody());
    }

    @Test
    void aKeyCanBeCreatedOverHttpAndThenUsed() {
        ResponseEntity<Map> created = rest.postForEntity("/api/v1/account/api-keys",
                new HttpEntity<>(Map.of("label", "from-http"), auth(issueKey())), Map.class);

        assertEquals(HttpStatus.OK, created.getStatusCode());
        String raw = (String) created.getBody().get("key");
        assertNotNull(raw, "the raw key is returned exactly once, at creation");
        assertTrue(raw.startsWith("qalab_"), raw);

        // The freshly issued key authenticates.
        ResponseEntity<String> res = rest.exchange("/api/v1/account/budget-policy", HttpMethod.GET,
                new HttpEntity<>(auth(raw)), String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
    }

    @Test
    void listingKeysNeverReturnsTheSecret() {
        String issuer = issueKey();
        rest.postForEntity("/api/v1/account/api-keys",
                new HttpEntity<>(Map.of("label", "listed"), auth(issuer)), Map.class);

        // Read the raw body: this endpoint returns a JSON array, and the point of the
        // assertion is what the bytes contain, not how they deserialise.
        ResponseEntity<String> listed = rest.exchange("/api/v1/account/api-keys", HttpMethod.GET,
                new HttpEntity<>(auth(issuer)), String.class);

        assertEquals(HttpStatus.OK, listed.getStatusCode());
        String body = listed.getBody();
        assertTrue(body.contains("listed"), body);
        assertTrue(!body.contains("keyHash") && !body.contains("salt"),
                "a list response must never expose hashes or salts: " + body);
    }

    @Test
    void aRevokedKeyStopsWorkingOverHttp() {
        ApiKeyService.IssuedKey issued = apiKeyService.issue(null, "to-revoke");

        assertEquals(HttpStatus.OK, rest.exchange("/api/v1/account/budget-policy", HttpMethod.GET,
                new HttpEntity<>(auth(issued.rawKey())), String.class).getStatusCode());

        rest.exchange("/api/v1/account/api-keys/" + issued.id(), HttpMethod.DELETE,
                new HttpEntity<>(auth(issueKey())), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, rest.exchange("/api/v1/account/budget-policy", HttpMethod.GET,
                        new HttpEntity<>(auth(issued.rawKey())), String.class).getStatusCode(),
                "a revoked key must stop authenticating immediately");
    }
}
