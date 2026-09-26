package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.service.ApiKeyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public bootstrap probe (B-013).
 *
 * <p>Answers "does this deployment need an API key?" so the CLI and the dashboard can
 * show an actionable message, instead of the client receiving a bare 401 and having to
 * guess. Deliberately reveals nothing sensitive: only whether a key exists and how to
 * create one.</p>
 */
@RestController
@RequestMapping("/api/v1/account")
public class V1BootstrapController {

    private final ApiKeyService apiKeyService;
    private final boolean requireApiKey;

    public V1BootstrapController(ApiKeyService apiKeyService,
                                 @Value("${qalab.security.require-api-key:false}") boolean requireApiKey) {
        this.apiKeyService = apiKeyService;
        this.requireApiKey = requireApiKey;
    }

    @GetMapping("/bootstrap")
    public ResponseEntity<Map<String, Object>> bootstrap() {
        boolean anyKey = apiKeyService.anyKeyExists();
        boolean enforced = requireApiKey || anyKey;
        return ResponseEntity.ok(Map.of(
                "apiKeyRequired", enforced,
                "apiKeysExist", anyKey,
                "createKeyEndpoint", "POST /api/v1/account/api-keys",
                "headerName", "Authorization",
                "headerScheme", "Bearer"));
    }
}
