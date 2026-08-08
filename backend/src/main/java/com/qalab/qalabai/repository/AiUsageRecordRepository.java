package com.qalab.qalabai.repository;

import com.qalab.qalabai.ai.gateway.AiCredentialMode;
import com.qalab.qalabai.ai.gateway.AiOperation;
import com.qalab.qalabai.ai.gateway.AiProviderType;
import com.qalab.qalabai.model.AiUsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AiUsageRecordRepository extends JpaRepository<AiUsageRecord, Long> {

    List<AiUsageRecord> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    List<AiUsageRecord> findByAccountIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            Long accountId, LocalDateTime from);

    List<AiUsageRecord> findByAccountIdAndCredentialModeAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            Long accountId, AiCredentialMode mode, LocalDateTime from);

    @Query("SELECT COALESCE(SUM(u.totalTokens), 0) FROM AiUsageRecord u " +
            "WHERE u.accountId = :accountId AND u.credentialMode = :mode AND u.createdAt >= :from")
    long sumTotalTokensByAccountAndModeSince(@Param("accountId") Long accountId,
                                             @Param("mode") AiCredentialMode mode,
                                             @Param("from") LocalDateTime from);

    @Query("SELECT u.operation, COUNT(u), COALESCE(SUM(u.totalTokens), 0) FROM AiUsageRecord u " +
            "WHERE u.accountId = :accountId GROUP BY u.operation")
    List<Object[]> sumByOperation(@Param("accountId") Long accountId);

    List<AiUsageRecord> findByOperation(AiOperation operation);

    List<AiUsageRecord> findByProvider(AiProviderType provider);

    List<AiUsageRecord> findByProjectId(Long projectId);
}
