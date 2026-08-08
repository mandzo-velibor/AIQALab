package com.qalab.qalabai.ai.gateway;

import com.qalab.qalabai.model.AiUsageRecord;
import com.qalab.qalabai.repository.AiUsageRecordRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records and queries AI usage. Usage is recorded for every credential mode;
 * only MANAGED records count toward the account allowance (see
 * {@code TokenBudgetService}).
 */
@Service
public class UsageService {

    private final AiUsageRecordRepository usageRepository;

    public UsageService(AiUsageRecordRepository usageRepository) {
        this.usageRepository = usageRepository;
    }

    public void recordUsage(Long accountId, Long projectId, String operationId,
                            com.qalab.qalabai.ai.gateway.AiOperation operation,
                            AiProviderType provider, String model,
                            AiCredentialMode credentialMode,
                            int inputTokens, int outputTokens,
                            boolean estimated, BigDecimal estimatedCost) {
        AiUsageRecord record = new AiUsageRecord();
        record.setAccountId(accountId);
        record.setProjectId(projectId);
        record.setOperationId(operationId);
        record.setOperation(operation);
        record.setProvider(provider);
        record.setModel(model);
        record.setCredentialMode(credentialMode);
        record.setInputTokens(inputTokens);
        record.setOutputTokens(outputTokens);
        record.setTotalTokens(inputTokens + outputTokens);
        record.setEstimated(estimated);
        record.setEstimatedCost(estimatedCost);
        usageRepository.save(record);
    }

    public List<AiUsageRecord> recentUsage(Long accountId, int limit) {
        return usageRepository.findByAccountIdOrderByCreatedAtDesc(accountId)
                .stream().limit(limit).toList();
    }

    public Map<String, Object> breakdownByOperation(Long accountId) {
        List<Object[]> rows = usageRepository.sumByOperation(accountId);
        Map<String, Object> breakdown = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String operation = String.valueOf(row[0]);
            long calls = ((Number) row[1]).longValue();
            long tokens = ((Number) row[2]).longValue();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("calls", calls);
            entry.put("tokens", tokens);
            breakdown.put(operation, entry);
        }
        return breakdown;
    }
}
