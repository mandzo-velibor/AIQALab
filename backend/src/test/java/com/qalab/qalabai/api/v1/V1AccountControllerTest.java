package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.ai.gateway.AiGateway;
import com.qalab.qalabai.ai.gateway.BudgetPolicy;
import com.qalab.qalabai.ai.gateway.TokenBudget;
import com.qalab.qalabai.ai.gateway.UsageService;
import com.qalab.qalabai.api.v1.dto.V1AccountUsageResponse;
import com.qalab.qalabai.model.Account;
import com.qalab.qalabai.service.AccountService;
import com.qalab.qalabai.service.TokenBudgetService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V1AccountControllerTest {

    private final AccountService accountService = mock(AccountService.class);
    private final TokenBudgetService budgetService = mock(TokenBudgetService.class);
    private final AiGateway aiGateway = mock(AiGateway.class);
    private final UsageService usageService = mock(UsageService.class);

    private final V1AccountController controller = new V1AccountController(
            null, accountService, budgetService, aiGateway, usageService);

    @Test
    void usageExposesBudgetBreakdownAndRecent() {
        Account account = new Account();
        when(accountService.defaultAccount()).thenReturn(account);
        when(aiGateway.currentBudget()).thenReturn(new TokenBudget(4000, 1248, true, BudgetPolicy.HARD));

        Map<String, Object> breakdown = new LinkedHashMap<>();
        Map<String, Object> analyze = new LinkedHashMap<>();
        analyze.put("calls", 2L);
        analyze.put("tokens", 900L);
        breakdown.put("ANALYZE", analyze);
        when(aiGateway.usageBreakdown()).thenReturn(breakdown);
        when(usageService.recentUsage(account.getId(), 20)).thenReturn(List.of());

        ResponseEntity<V1AccountUsageResponse> response = controller.usage();

        assertEquals(4000L, response.getBody().budget().limit());
        assertEquals(1248L, response.getBody().budget().used());
        assertEquals(2752L, response.getBody().budget().remaining());
        assertEquals(1, response.getBody().breakdown().size());
        assertEquals("ANALYZE", response.getBody().breakdown().get(0).operation());
        assertEquals(2, response.getBody().breakdown().get(0).calls());
        assertEquals(900, response.getBody().breakdown().get(0).tokens());
        assertTrue(response.getBody().recent().isEmpty());
    }
}
