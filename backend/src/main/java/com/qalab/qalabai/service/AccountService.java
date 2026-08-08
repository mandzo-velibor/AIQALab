package com.qalab.qalabai.service;

import com.qalab.qalabai.ai.gateway.Plan;
import com.qalab.qalabai.model.Account;
import com.qalab.qalabai.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

/**
 * Resolves the account owning the token allowance. In this single-tenant
 * sprint there is exactly one default account (FREE plan); the allowance
 * belongs to it, shared by all projects.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Account defaultAccount() {
        return accountRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setName("default");
                    account.setPlan(Plan.FREE);
                    account.setBudgetPolicy(com.qalab.qalabai.ai.gateway.BudgetPolicy.defaultFor(Plan.FREE));
                    Account saved = accountRepository.save(account);
                    log.info("Created default account {} with plan {} policy {}", saved.getId(), saved.getPlan(), saved.getBudgetPolicy());
                    return saved;
                });
    }

    /**
     * Updates the budget enforcement policy for the default account. {@code null}
     * resets to the plan default.
     */
    public Account updateBudgetPolicy(com.qalab.qalabai.ai.gateway.BudgetPolicy policy) {
        Account account = defaultAccount();
        account.setBudgetPolicy(policy);
        Account saved = accountRepository.save(account);
        log.info("Account {} budget policy -> {}", saved.getId(), saved.getBudgetPolicy());
        return saved;
    }

    @Bean
    public CommandLineRunner ensureDefaultAccount(AccountService accountService) {
        return args -> accountService.defaultAccount();
    }
}
