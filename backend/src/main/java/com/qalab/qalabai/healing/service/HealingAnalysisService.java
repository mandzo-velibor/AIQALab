package com.qalab.qalabai.healing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.healing.ai.CandidateEvaluation;
import com.qalab.qalabai.healing.ai.HealingAiEvaluator;
import com.qalab.qalabai.healing.candidates.CandidateRanker;
import com.qalab.qalabai.healing.candidates.LocatorCandidateGenerator;
import com.qalab.qalabai.healing.candidates.LocatorCandidateValidator;
import com.qalab.qalabai.healing.classification.FailureClassifier;
import com.qalab.qalabai.healing.context.DomContextExtractor;
import com.qalab.qalabai.healing.context.FailureContextFactory;
import com.qalab.qalabai.healing.model.DomSnapshot;
import com.qalab.qalabai.healing.model.FailureClassification;
import com.qalab.qalabai.healing.model.FailureContext;
import com.qalab.qalabai.healing.model.HealingConfidence;
import com.qalab.qalabai.healing.model.HealingProposal;
import com.qalab.qalabai.healing.model.LocatorCandidate;
import com.qalab.qalabai.healing.model.LocatorStrategy;
import com.qalab.qalabai.healing.service.HealingOutcome.FailureClassificationView;
import com.qalab.qalabai.locator.intelligence.LocatorHealthService;
import com.qalab.qalabai.locator.intelligence.LocatorQualityScorer;
import com.qalab.qalabai.locator.intelligence.LocatorSemanticAnalyzer;
import com.qalab.qalabai.locator.intelligence.LocatorStabilityAnalyzer;
import com.qalab.qalabai.locator.intelligence.LocatorStrategyDetector;
import com.qalab.qalabai.locator.intelligence.model.LocatorHealth;
import com.qalab.qalabai.locator.intelligence.model.QualityScore;
import com.qalab.qalabai.locator.intelligence.model.SemanticResult;
import com.qalab.qalabai.locator.intelligence.model.StabilityResult;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.TestExecutionRepository;
import com.qalab.qalabai.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the self-healing pipeline:
 *
 * <pre>
 * TEST FAILURE → FAILURE CLASSIFICATION → LOCATOR FAILURE? → COLLECT CONTEXT
 * → GENERATE CANDIDATES → VALIDATE → RANK → AI EVALUATION → HEALING PROPOSAL
 * </pre>
 *
 * <p>The pipeline never modifies source files. It produces an explainable,
 * human-reviewable {@link HealingProposal}.</p>
 */
@Service
public class HealingAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(HealingAnalysisService.class);

    private final FailureClassifier classifier;
    private final FailureContextFactory contextFactory;
    private final DomContextExtractor domContextExtractor;
    private final LocatorCandidateGenerator candidateGenerator;
    private final LocatorCandidateValidator candidateValidator;
    private final CandidateRanker candidateRanker;
    private final HealingAiEvaluator aiEvaluator;
    private final HealingProposalService proposalService;
    private final TestExecutionRepository executionRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final LocatorStrategyDetector strategyDetector;
    private final LocatorStabilityAnalyzer stabilityAnalyzer;
    private final LocatorSemanticAnalyzer semanticAnalyzer;
    private final LocatorQualityScorer qualityScorer;
    private final LocatorHealthService healthService;

    public HealingAnalysisService(FailureClassifier classifier,
                                  FailureContextFactory contextFactory,
                                  DomContextExtractor domContextExtractor,
                                  LocatorCandidateGenerator candidateGenerator,
                                  LocatorCandidateValidator candidateValidator,
                                  CandidateRanker candidateRanker,
                                  HealingAiEvaluator aiEvaluator,
                                  HealingProposalService proposalService,
                                  TestExecutionRepository executionRepository,
                                  ProjectRepository projectRepository,
                                  ObjectMapper objectMapper,
                                  LocatorStrategyDetector strategyDetector,
                                  LocatorStabilityAnalyzer stabilityAnalyzer,
                                  LocatorSemanticAnalyzer semanticAnalyzer,
                                  LocatorQualityScorer qualityScorer,
                                  LocatorHealthService healthService) {
        this.classifier = classifier;
        this.contextFactory = contextFactory;
        this.domContextExtractor = domContextExtractor;
        this.candidateGenerator = candidateGenerator;
        this.candidateValidator = candidateValidator;
        this.candidateRanker = candidateRanker;
        this.aiEvaluator = aiEvaluator;
        this.proposalService = proposalService;
        this.executionRepository = executionRepository;
        this.projectRepository = projectRepository;
        this.objectMapper = objectMapper;
        this.strategyDetector = strategyDetector;
        this.stabilityAnalyzer = stabilityAnalyzer;
        this.semanticAnalyzer = semanticAnalyzer;
        this.qualityScorer = qualityScorer;
        this.healthService = healthService;
    }

    /** Runs the pipeline for a failure context posted directly to the API. */
    public HealingOutcome analyze(FailureContext context, ProjectContext project) {
        ensureClassification(context);
        FailureClassificationView view = classificationView(context);

        if (context.getClassification() != FailureClassification.LOCATOR_FAILURE) {
            log.info("Failure classified as {} — no locator healing attempted for run {}",
                    context.getClassification(), context.getRunId());
            return new HealingOutcome(context, view, List.of(), null, false,
                    "Failure classified as " + context.getClassification()
                            + "; locator healing is only attempted for locator failures.");
        }

        DomSnapshot snapshot = collectDom(context);
        List<LocatorCandidate> candidates = generateAndValidate(context, snapshot);
        if (candidates.isEmpty()) {
            return new HealingOutcome(context, view, candidates, null, true,
                    "No locator candidates could be generated from the current page context.");
        }

        HealingProposal proposal = buildProposal(context, snapshot, candidates, project, view);
        return new HealingOutcome(context, view, candidates, proposal, true,
                proposal != null ? "Healing proposal created." : "Healing analysis produced no proposal.");
    }

    /** Runs the pipeline for a persisted execution, building context from its record. */
    public HealingOutcome analyzeExecution(Long executionId, Long projectId) {
        TestExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found: " + executionId));
        String baseUrl = resolveBaseUrl(projectId);
        FailureContext context = contextFactory.fromExecution(projectId, String.valueOf(executionId), execution, baseUrl);
        ProjectContext project = new ProjectContext(projectId, null, baseUrl, null);
        return analyze(context, project);
    }

    private String resolveBaseUrl(Long projectId) {
        if (projectId == null) {
            return null;
        }
        try {
            return projectRepository.findById(projectId)
                    .map(com.qalab.qalabai.model.Project::getBaseUrl)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Unable to resolve baseUrl for project {}: {}", projectId, e.getMessage());
            return null;
        }
    }

    private void ensureClassification(FailureContext context) {
        if (context.getClassification() == null) {
            FailureClassifier.Classification c = classifier.classify(
                    new com.qalab.qalabai.healing.model.ExecutionTestResult(
                            context.getTestId(), context.getTestName(), context.getTestFile(),
                            "FAILED", context.getAction(), context.getOriginalLocator(),
                            context.getSourceCode(), context.getSourceLine(), context.getError(),
                            context.getStackTrace(), context.getLogs(), context.getScreenshot(),
                            context.getTrace(), context.getVideo(), context.getCurrentUrl(),
                            context.getPageTitle(), context.getDomSnapshot(),
                            context.getExecutionTimestamp()));
            context.setClassification(c.type());
            context.setClassificationConfidence(c.confidence());
            context.setClassificationReason(c.reason());
        }
    }

    private FailureClassificationView classificationView(FailureContext context) {
        return new FailureClassificationView(
                context.getClassification() != null ? context.getClassification().name() : FailureClassification.UNKNOWN.name(),
                context.getClassificationConfidence() != null ? context.getClassificationConfidence() : 0.0,
                context.getClassificationReason());
    }

    private DomSnapshot collectDom(FailureContext context) {
        if (context.getRelevantHtml() != null && !context.getRelevantHtml().isBlank()) {
            return new DomSnapshot(context.getCurrentUrl(), context.getPageTitle(), context.getRelevantHtml());
        }
        if (context.getDomSnapshot() != null && !context.getDomSnapshot().isBlank()) {
            return domContextExtractor.extractFromHtml(context.getDomSnapshot(), context.getCurrentUrl(), context.getPageTitle());
        }
        if (context.getCurrentUrl() != null && !context.getCurrentUrl().isBlank()) {
            return domContextExtractor.extractLive(context.getCurrentUrl());
        }
        return new DomSnapshot(null, null, "");
    }

    private List<LocatorCandidate> generateAndValidate(FailureContext context, DomSnapshot snapshot) {
        List<LocatorCandidate> generated = candidateGenerator.generate(context.getOriginalLocator(), snapshot);
        List<LocatorCandidate> validated = generated.stream()
                .map(c -> candidateValidator.validate(c, snapshot))
                .toList();
        List<LocatorCandidate> ranked = candidateRanker.rank(validated, context.getOriginalLocator());
        return ranked.stream().filter(c -> c.matchedElementCount() > 0).toList();
    }

    private HealingProposal buildProposal(FailureContext context, DomSnapshot snapshot,
                                          List<LocatorCandidate> candidates,
                                          ProjectContext project,
                                          FailureClassificationView view) {
        List<LocatorCandidate> enriched = enrichWithIntelligence(candidates);

        CandidateEvaluation aiEval = null;
        String aiRiskNote = "";
        try {
            aiEval = aiEvaluator.evaluate(context.getOriginalLocator(), context.getError(), snapshot,
                    enriched, project, "op-healing-" + UUID.randomUUID().toString().substring(0, 8));
        } catch (Exception e) {
            log.warn("AI evaluation failed for run {}; using deterministic ranking only: {}",
                    context.getRunId(), e.getMessage());
            aiRiskNote = " (AI evaluation unavailable: " + e.getMessage() + ")";
        }

        LocatorCandidate recommended = pickRecommended(enriched, aiEval);
        if (recommended == null) {
            return null;
        }

        double deterministicScore = recommended.score();
        double aiConfidence = aiEval != null ? aiEval.confidence() : deterministicScore;
        double confidence = round(0.5 * deterministicScore + 0.5 * aiConfidence);
        confidence = Math.max(0.0, Math.min(1.0, confidence));

        boolean safeToApply = deterministicSafe(recommended)
                && (aiEval == null || aiEval.safeToApply())
                && confidence >= 0.70;

        LocatorIntelligenceFields original = intelligenceOf(context.getOriginalLocator(), null);
        LocatorIntelligenceFields recommendedInt = intelligenceOf(recommended.locator(), recommended.strategy());

        String reason = buildReason(recommended, aiEval, deterministicScore, aiRiskNote,
                recommendedInt, original);

        HealingProposal proposal = new HealingProposal();
        proposal.setProposalId("heal-" + UUID.randomUUID().toString().substring(0, 8));
        proposal.setProjectId(context.getProjectId() != null ? context.getProjectId()
                : (project != null ? project.getDatabaseId() : null));
        proposal.setRunId(context.getRunId());
        proposal.setTestId(context.getTestId());
        proposal.setTestName(context.getTestName() != null ? context.getTestName() : context.getTestFile());
        proposal.setClassification(view.type());
        proposal.setOriginalLocator(context.getOriginalLocator());
        proposal.setRecommendedLocator(recommended.locator());
        proposal.setConfidence(confidence);
        proposal.setConfidenceLabel(HealingConfidence.from(confidence).name());
        proposal.setSafeToApply(safeToApply);
        proposal.setReason(reason);
        proposal.setAlternativesJson(alternativesJson(enriched, recommended.locator()));
        proposal.setOriginalLocatorHealth(original.health().name());
        proposal.setOriginalLocatorStability(original.stability().score());
        proposal.setRecommendedLocatorHealth(recommendedInt.health().name());
        proposal.setRecommendedLocatorStability(recommendedInt.stability().score());
        proposal.setRecommendedStabilityLevel(recommendedInt.stability().level().name());
        proposal.setRecommendedSemanticScore(recommendedInt.semantic().score());
        proposal.setRecommendedQualityScore(recommendedInt.quality().total());

        return proposalService.create(proposal);
    }

    /**
     * Appends deterministic intelligence (stability, semantic, quality) to each
     * candidate's reason so the AI evaluation prompt carries the quality scores.
     */
    private List<LocatorCandidate> enrichWithIntelligence(List<LocatorCandidate> candidates) {
        return candidates.stream().map(c -> {
            LocatorIntelligenceFields info = intelligenceOf(c.locator(), c.strategy());
            String suffix = ", stability=" + String.format("%.0f/25", info.stability().score())
                    + " (" + info.stability().level() + "), semantic="
                    + String.format("%.0f/25", info.semantic().score())
                    + ", quality=" + String.format("%.0f/100", info.quality().total());
            return new LocatorCandidate(c.locator(), c.strategy(), c.score(), c.unique(), c.visible(),
                    c.enabled(), c.matchedElementCount(), c.reason() + suffix);
        }).toList();
    }

    private LocatorIntelligenceFields intelligenceOf(String locator, LocatorStrategy strategyHint) {
        LocatorStrategy strategy = strategyHint != null ? strategyHint : strategyDetector.detect(locator);
        StabilityResult stability = stabilityAnalyzer.analyze(locator);
        SemanticResult semantic = semanticAnalyzer.analyze(locator, strategy, null);
        QualityScore quality = qualityScorer.score(
                qualityScorer.uniquenessFromCount(1), semantic.score(), stability.score(),
                qualityScorer.maintainability(strategy), qualityScorer.resilience(strategy));
        LocatorHealth health = healthService.classify(stability.score(), null, 0, true);
        return new LocatorIntelligenceFields(strategy, stability, semantic, quality, health);
    }

    private record LocatorIntelligenceFields(LocatorStrategy strategy,
                                             StabilityResult stability,
                                             SemanticResult semantic,
                                             QualityScore quality,
                                             LocatorHealth health) {
    }

    private LocatorCandidate pickRecommended(List<LocatorCandidate> candidates, CandidateEvaluation aiEval) {
        if (aiEval != null && aiEval.recommendedLocator() != null && !aiEval.recommendedLocator().isBlank()) {
            for (LocatorCandidate candidate : candidates) {
                if (candidate.locator().equals(aiEval.recommendedLocator())) {
                    return candidate;
                }
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private boolean deterministicSafe(LocatorCandidate recommended) {
        if (recommended.matchedElementCount() != 1) {
            return false;
        }
        if (!recommended.visible()) {
            return false;
        }
        if (!recommended.enabled()) {
            return false;
        }
        return recommended.strategy() != LocatorStrategy.XPATH;
    }

    private String buildReason(LocatorCandidate recommended, CandidateEvaluation aiEval,
                               double deterministicScore, String aiRiskNote,
                               LocatorIntelligenceFields recommendedInt,
                               LocatorIntelligenceFields originalInt) {
        StringBuilder sb = new StringBuilder();
        if (aiEval != null && aiEval.reason() != null && !aiEval.reason().isBlank()) {
            sb.append(aiEval.reason());
        } else {
            sb.append("Deterministic ranking selected the best validated candidate.");
        }
        sb.append(" [deterministic score: ").append(String.format("%.2f", deterministicScore))
                .append(", strategy: ").append(recommended.strategy())
                .append(", unique: ").append(recommended.unique())
                .append(", visible: ").append(recommended.visible())
                .append(", enabled: ").append(recommended.enabled()).append("]");
        sb.append(" Intelligence: original health=").append(originalInt.health())
                .append(" (stability ").append(String.format("%.0f/25", originalInt.stability().score()))
                .append("), recommended health=").append(recommendedInt.health())
                .append(" (stability ").append(String.format("%.0f/25", recommendedInt.stability().score()))
                .append(", semantic ").append(String.format("%.0f/25", recommendedInt.semantic().score()))
                .append(", quality ").append(String.format("%.0f/100", recommendedInt.quality().total()))
                .append(").");
        if (aiEval != null && aiEval.risks() != null && !aiEval.risks().isEmpty()) {
            sb.append(" Risks: ").append(String.join("; ", aiEval.risks())).append(".");
        }
        sb.append(aiRiskNote);
        return sb.toString();
    }

    private String alternativesJson(List<LocatorCandidate> candidates, String recommendedLocator) {
        List<LocatorCandidate> alternatives = candidates.stream()
                .filter(c -> !c.locator().equals(recommendedLocator))
                .toList();
        try {
            return objectMapper.writeValueAsString(alternatives);
        } catch (Exception e) {
            return "[]";
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
