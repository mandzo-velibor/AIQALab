package com.qalab.qalabai.model;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "test_scenario")
public class TestScenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_plan_id", nullable = false)
    private TestPlan testPlan;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String priority;

    @Column(length = 1000)
    private String description;

    @ElementCollection
    @CollectionTable(name = "test_scenario_steps", joinColumns = @JoinColumn(name = "scenario_id"))
    @Column(name = "step", length = 500)
    @OrderColumn(name = "step_order")
    private List<String> steps = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "test_scenario_elements", joinColumns = @JoinColumn(name = "scenario_id"))
    @Column(name = "element_name", length = 200)
    private List<String> requiredElements = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TestPlan getTestPlan() {
        return testPlan;
    }

    public void setTestPlan(TestPlan testPlan) {
        this.testPlan = testPlan;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getSteps() {
        return steps;
    }

    public void setSteps(List<String> steps) {
        this.steps = steps;
    }

    public List<String> getRequiredElements() {
        return requiredElements;
    }

    public void setRequiredElements(List<String> requiredElements) {
        this.requiredElements = requiredElements;
    }
}
