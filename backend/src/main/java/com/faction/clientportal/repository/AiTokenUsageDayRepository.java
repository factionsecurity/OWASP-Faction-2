package com.faction.clientportal.repository;

import com.faction.clientportal.dto.AiTokenUsageDayDto;
import com.faction.clientportal.model.AiTokenUsageDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface AiTokenUsageDayRepository extends JpaRepository<AiTokenUsageDay, String> {

    /**
     * Adds one call's tokens to today's row for this user/provider/model, creating it on first use.
     * An upsert rather than read-modify-write so two concurrent AI requests can't lose each other's
     * tokens. Transactional here, not on the caller, so a ledger failure rolls back only this
     * statement and surfaces as an exception the caller can swallow.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO ai_token_usage_daily
                (id, usage_date, username, provider_name, model, input_tokens, output_tokens, request_count)
            VALUES (:id, :usageDate, :username, :providerName, :model, :inputTokens, :outputTokens, 1)
            ON CONFLICT (usage_date, username, provider_name, model) DO UPDATE SET
                input_tokens  = ai_token_usage_daily.input_tokens  + EXCLUDED.input_tokens,
                output_tokens = ai_token_usage_daily.output_tokens + EXCLUDED.output_tokens,
                request_count = ai_token_usage_daily.request_count + 1
            """, nativeQuery = true)
    void addUsage(@Param("id") String id,
                  @Param("usageDate") LocalDate usageDate,
                  @Param("username") String username,
                  @Param("providerName") String providerName,
                  @Param("model") String model,
                  @Param("inputTokens") long inputTokens,
                  @Param("outputTokens") long outputTokens);

    /** Daily totals across all users/providers/models. Days with no usage are simply absent. */
    @Query("""
            SELECT new com.faction.clientportal.dto.AiTokenUsageDayDto(
                       u.usageDate, SUM(u.inputTokens), SUM(u.outputTokens), SUM(u.requestCount))
            FROM AiTokenUsageDay u
            WHERE u.usageDate >= :from AND u.usageDate <= :to
            GROUP BY u.usageDate
            ORDER BY u.usageDate
            """)
    List<AiTokenUsageDayDto> dailyTotals(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
