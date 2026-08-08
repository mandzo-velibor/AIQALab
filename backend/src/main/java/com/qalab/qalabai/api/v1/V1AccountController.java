package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.ai.gateway.AiGateway;
import com.qalab.qalabai.ai.gateway.BudgetPolicy;
import com.qalab.qalabai.ai.gateway.TokenBudget;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.v1.dto.V1BudgetPolicyResponse;
import com.qalab.qalabai.api.v1.dto.V1UpdateBudgetPolicyRequest;
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

@RestController
@RequestMapping("/api/v1/account")
public class V1AccountController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1AccountController.class);

    private final AccountService accountService;
    private final TokenBudgetService budgetService;
    private final AiGateway aiGateway;

    public V1AccountController(ProjectContextResolver contextResolver,
                               AccountService accountService,
                               TokenBudgetService budgetService,
                               AiGateway aiGateway) {
        super(contextResolver);
        this.accountService = accountService;
        this.budgetService = budgetService;
        this.aiGateway = aiGateway;
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
