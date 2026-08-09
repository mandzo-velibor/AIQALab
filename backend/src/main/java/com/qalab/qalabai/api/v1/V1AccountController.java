package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.ai.gateway.AiGateway;
import com.qalab.qalabai.ai.gateway.BudgetPolicy;
import com.qalab.qalabai.ai.gateway.TokenBudget;
import com.qalab.qalabai.ai.gateway.UsageService;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.v1.dto.V1AccountUsageResponse;
import com.qalab.qalabai.api.v1.dto.V1BudgetPolicyResponse;
import com.qalab.qalabai.api.v1.dto.V1UpdateBudgetPolicyRequest;
import com.qalab.qalabai.api.v1.dto.V1UsageRecordResponse;
import com.qalab.qalabai.model.Account;
import com.qalab.qalabai.service.AccountService;
import com.qalab.qalabai.service.ProjectContextResolver;
import com.qalab.qalabai.service.TokenBudgetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/account")
public class V1AccountController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1AccountController.class);

    private static final int RECENT_USAGE_LIMIT = 20;

    private final AccountService accountService;
    private final TokenBudgetService budgetService;
    private final AiGateway aiGateway;
    private final UsageService usageService;

    public V1AccountController(ProjectContextResolver contextResolver,
                               AccountService accountService,
                               TokenBudgetService budgetService,
                               AiGateway aiGateway,
                               UsageService usageService) {
        super(contextResolver);
        this.accountService = accountService;
        this.budgetService = budgetService;
        this.aiGateway = aiGateway;
        this.usageService = usageService;
    }

    @GetMapping("/budget-policy")
    public ResponseEntity<V1BudgetPolicyResponse> getBudgetPolicy() {
        TokenBudget budget = aiGateway.currentBudget();
        Account account = accountService.defaultAccount();
        return ResponseEntity.ok(toResponse(account, budget));
    }

    @PatchMapping("/budget-policy")
    public ResponseEntity<V1BudgetPolicyResponse> updateBudgetPolicy(@RequestBody V1UpdateBudgetPolicyRequest request) {
        if (request == null || request.policy() == null) {
            throw ApiException.invalidRequest("policy is required (HARD, SOFT or NONE)");
        }
        log.info("PATCH /api/v1/account/budget-policy -> {}", request.policy());
        Account account = accountService.updateBudgetPolicy(request.policy());
        TokenBudget budget = aiGateway.currentBudget();
        return ResponseEntity.ok(toResponse(account, budget));
    }

    @GetMapping("/usage")
    public ResponseEntity<V1AccountUsageResponse> usage() {
        Account account = accountService.defaultAccount();
        TokenBudget budget = aiGateway.currentBudget();
        List<V1AccountUsageResponse.V1UsageByOperation> breakdown = breakdownOf(aiGateway.usageBreakdown());
        List<V1UsageRecordResponse> recent = usageService.recentUsage(account.getId(), RECENT_USAGE_LIMIT)
                .stream().map(V1UsageRecordResponse::from).toList();
        return ResponseEntity.ok(new V1AccountUsageResponse(toResponse(account, budget), breakdown, recent));
    }

    private List<V1AccountUsageResponse.V1UsageByOperation> breakdownOf(Map<String, Object> raw) {
        List<V1AccountUsageResponse.V1UsageByOperation> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> counts) {
                long calls = counts.get("calls") instanceof Number n ? n.longValue() : 0L;
                long tokens = counts.get("tokens") instanceof Number n ? n.longValue() : 0L;
                result.add(new V1AccountUsageResponse.V1UsageByOperation(entry.getKey(), calls, tokens));
            }
        }
        return result;
    }

    private V1BudgetPolicyResponse toResponse(Account account, TokenBudget budget) {
        return new V1BudgetPolicyResponse(
                budget.getPolicy(),
                account.getPlan() != null ? account.getPlan().name() : null,
                budget.getLimit(),
                budget.getUsed(),
                budget.getRemaining(),
                budget.isHardStop(),
                budget.isSoftExceeded(),
                LocalDateTime.now()
        );
    }
}
