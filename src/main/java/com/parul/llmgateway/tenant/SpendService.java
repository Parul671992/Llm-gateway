package com.parul.llmgateway.tenant;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SpendService {

    private static final Logger log = LoggerFactory.getLogger(SpendService.class);
    private static final Duration SPEND_CACHE_TTL = Duration.ofDays(40);

    private final JdbcClient jdbcClient;
    private final StringRedisTemplate redis;
    private final BudgetService budgetService;

    public SpendService(JdbcClient jdbcClient, StringRedisTemplate redis, BudgetService budgetService) {
        this.jdbcClient = jdbcClient;
        this.redis = redis;
        this.budgetService = budgetService;
    }

    // Write-through: add to PostgreSQL (the truth), then copy the new total into Redis
    public void recordSpend(Long tenantId, long cost) {
        String month = currentMonth();

        Long newTotal = jdbcClient.sql("""
                INSERT INTO tenant_monthly_spend (tenant_id, month, spent_micros)
                VALUES (:tenantId, :month, :cost)
                ON CONFLICT (tenant_id, month)
                DO UPDATE SET spent_micros = tenant_monthly_spend.spent_micros + EXCLUDED.spent_micros
                RETURNING spent_micros
                """)
                .param("tenantId", tenantId)
                .param("month", month)
                .param("cost", cost)
                .query(Long.class)
                .single();

        writeCache(tenantId, month, newTotal);
    }

    public long getSpentMicros(Long tenantId) {
        String month = currentMonth();
        String cached = readCache(tenantId, month);
        if (cached != null) {
            return Long.parseLong(cached);
        }
        long spent = jdbcClient.sql("""
                SELECT spent_micros FROM tenant_monthly_spend
                WHERE tenant_id = :tenantId AND month = :month
                """)
                .param("tenantId", tenantId)
                .param("month", month)
                .query(Long.class)
                .optional()
                .orElse(0L);
        writeCache(tenantId, month, spent);
        return spent;
    }

    public boolean isOverBudget(Long tenantId) {
        long budgetMicros = budgetService.getMonthlyBudget(tenantId).movePointRight(6).longValue();
        return getSpentMicros(tenantId) >= budgetMicros;
    }

    private static String currentMonth() {
        return YearMonth.now(ZoneOffset.UTC).toString();
    }

    private String readCache(Long tenantId, String month) {
        try {
            return redis.opsForValue().get("spend:" + tenantId + ":" + month);
        } catch (DataAccessException ex) {
            log.warn("Spend cache read failed, using database: {}", ex.getMessage());
            return null;
        }
    }

    private void writeCache(Long tenantId, String month, long total) {
        try {
            redis.opsForValue().set("spend:" + tenantId + ":" + month, String.valueOf(total), SPEND_CACHE_TTL);
        } catch (DataAccessException ex) {
            log.warn("Spend cache write failed: {}", ex.getMessage());
        }
    }
}