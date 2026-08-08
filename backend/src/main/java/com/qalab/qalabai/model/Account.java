package com.qalab.qalabai.model;

import com.qalab.qalabai.ai.gateway.BudgetPolicy;
import com.qalab.qalabai.ai.gateway.Plan;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * An account owns the token allowance. The allowance belongs to the account,
 * NOT to an individual project — all projects share the same account budget.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Plan plan = Plan.FREE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BudgetPolicy budgetPolicy = BudgetPolicy.HARD;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Plan getPlan() {
        return plan;
    }

    public void setPlan(Plan plan) {
        this.plan = plan;
    }

    public BudgetPolicy getBudgetPolicy() {
        return budgetPolicy;
    }

    public void setBudgetPolicy(BudgetPolicy budgetPolicy) {
        this.budgetPolicy = budgetPolicy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
