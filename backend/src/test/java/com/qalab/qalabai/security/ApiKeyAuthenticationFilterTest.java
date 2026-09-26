package com.qalab.qalabai.security;

import com.qalab.qalabai.api.ErrorCode;
import com.qalab.qalabai.model.Account;
import com.qalab.qalabai.model.ApiKey;
import com.qalab.qalabai.repository.AccountRepository;
import com.qalab.qalabai.service.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * There was no authentication of any kind: any client that could reach the port could
 * spend AI budget, read every project's data, and mutate stored test source via the
 * healing endpoints. These tests pin the replacement.
 */
class ApiKeyAuthenticationFilterTest {

    private ApiKeyService apiKeyService;
    private AccountRepository accountRepository;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        accountRepository = mock(AccountRepository.class);
        mapper = new ObjectMapper();
    }

    private ApiKeyAuthenticationFilter filter(boolean requireApiKey, boolean anyKeyExists) {
        when(apiKeyService.anyKeyExists()).thenReturn(anyKeyExists);
        // The production filter takes ObjectProviders so JPA is resolved per request
        // rather than at Tomcat startup; the tests supply fixed instances.
        return new ApiKeyAuthenticationFilter(constant(apiKeyService), constant(accountRepository),
                mapper, requireApiKey);
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> constant(T value) {
        org.springframework.beans.factory.ObjectProvider<T> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }

    private Account account(long id) {
        Account a = new Account();
        a.setId(id);
        return a;
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/projects");
        if (authorization != null) {
            req.addHeader("Authorization", authorization);
        }
        return req;
    }

    @Test
    void aValidKeyIsResolvedToItsAccountAndPlacedOnTheRequest() throws Exception {
        when(apiKeyService.resolveAccountId("qalab_good")).thenReturn(Optional.of(7L));
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account(7L)));
        ApiKeyAuthenticationFilter f = filter(true, true);

        MockHttpServletRequest req = request("Bearer qalab_good");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, res, chain);

        org.mockito.Mockito.verify(chain).doFilter(req, res);
        assertNotNull(req.getAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE));
        AuthPrincipal principal = ApiKeyAuthenticationFilter.principalOf(req);
        assertEquals(7L, principal.accountId());
        assertEquals("FREE", principal.plan());
    }

    @Test
    void aRequestWithNoKeyIsRejectedWhenAKeyExists() throws Exception {
        ApiKeyAuthenticationFilter f = filter(false, true);

        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(request(null), res, chain);

        assertEquals(401, res.getStatus());
        org.mockito.Mockito.verify(chain, org.mockito.Mockito.never()).doFilter(any(), any());
    }

    @Test
    void anUnknownKeyIsRejected() throws Exception {
        when(apiKeyService.resolveAccountId("qalab_wrong")).thenReturn(Optional.empty());
        ApiKeyAuthenticationFilter f = filter(true, true);

        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(request("Bearer qalab_wrong"), res, chain);

        assertEquals(401, res.getStatus());
        org.mockito.Mockito.verify(chain, org.mockito.Mockito.never()).doFilter(any(), any());
    }

    @Test
    void aMalformedAuthorizationHeaderIsRejected() throws Exception {
        ApiKeyAuthenticationFilter f = filter(true, true);
        FilterChain chain = mock(FilterChain.class);

        for (String header : new String[]{"", "Bearer", "Bearer ", "Basic abc", "bearer qalab_x", "qalab_x"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            f.doFilter(request(header), res, chain);
            assertEquals(401, res.getStatus(), "should reject header: '" + header + "'");
        }
    }

    @Test
    void rejectionUsesTheStandardErrorEnvelopeSoClientsCanParseIt() throws Exception {
        ApiKeyAuthenticationFilter f = filter(true, true);
        MockHttpServletResponse res = new MockHttpServletResponse();

        f.doFilter(request(null), res, mock(FilterChain.class));

        assertEquals("application/json", res.getContentType());
        String body = res.getContentAsString();
        assertTrue(body.contains("\"error\""), "must use the shared envelope: " + body);
        assertTrue(body.contains(ErrorCode.UNAUTHENTICATED), body);
        // The message must tell the operator what to do, not just that it failed.
        assertTrue(body.contains("Authorization: Bearer"), body);
    }

    @Test
    void aFreshInstallWithNoKeysStaysUsable() throws Exception {
        // Trust-on-first-use: nothing to authenticate with yet, so refusing every
        // request would make a fresh local install unusable. ADR 0001 records the
        // accepted residual risk that an operator who never issues a key is
        // unprotected.
        ApiKeyAuthenticationFilter f = filter(false, false);
        MockHttpServletRequest req = request(null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, res, chain);

        org.mockito.Mockito.verify(chain).doFilter(req, res);
        assertNull(req.getAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE));
    }

    @Test
    void requiringAKeyBlocksEvenAFreshInstallWithNoKeys() throws Exception {
        ApiKeyAuthenticationFilter f = filter(true, false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(request(null), res, chain);

        assertEquals(401, res.getStatus());
        org.mockito.Mockito.verify(chain, org.mockito.Mockito.never()).doFilter(any(), any());
    }

    @Test
    void aKeyWhoseAccountVanishedIsRejectedRatherThanTrusted() throws Exception {
        // A dangling key must not authenticate as "some account".
        when(apiKeyService.resolveAccountId("qalab_orphan")).thenReturn(Optional.of(99L));
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());
        ApiKeyAuthenticationFilter f = filter(true, true);

        MockHttpServletRequest req = request("Bearer qalab_orphan");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req, res, chain);

        assertEquals(401, res.getStatus());
        assertNull(req.getAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE));
    }

    @Test
    void thePrincipalHelperReturnsNullWhenUnauthenticated() {
        assertNull(ApiKeyAuthenticationFilter.principalOf(new MockHttpServletRequest()));
    }

    @Test
    void keyRecordsNeverExposeTheSecret() {
        // The list view must not carry the hash or salt.
        ApiKey key = new ApiKey();
        key.setId(1L);
        key.setLabel("cli");
        key.setKeyHash("deadbeef");
        key.setSalt("cafe");

        for (var method : com.qalab.qalabai.api.v1.V1ApiKeyController.KeyView.class.getRecordComponents()) {
            String name = method.getName().toLowerCase();
            assertFalse(name.contains("hash"), "KeyView must not expose the hash");
            assertFalse(name.contains("salt"), "KeyView must not expose the salt");
            assertFalse(name.contains("key") && name.equals("key"), "KeyView must not expose the raw key");
        }
    }
}
