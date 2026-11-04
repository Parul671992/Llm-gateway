package com.parul.llmgateway.tenant;

import java.math.BigDecimal;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);
    private static final Duration BUDGET_CACHE_TTL = Duration.ofHours(24);

    private final TenantRepository tenantRepository;
    private final StringRedisTemplate redis;

    public BudgetService(TenantRepository tenantRepository, StringRedisTemplate redis) {
        this.tenantRepository = tenantRepository;
        this.redis = redis;
    }

    // WRITE-THROUGH: update the database, then the cache, together
    @Transactional
    public BigDecimal setMonthlyBudget(Long tenantId, BigDecimal newBudget) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        tenant.setMonthlyBudgetUsd(newBudget);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                writeCache(tenantId, newBudget);
            }
        });
        return newBudget;
    }

    // READ: cache first; if the cache is cold, load from the database and fill it
    @Transactional(readOnly = true)
    public BigDecimal getMonthlyBudget(Long tenantId) {
        String cached = readCache(tenantId);
        if (cached != null) {
            return new BigDecimal(cached);
        }
        BigDecimal budget = tenantRepository.findById(tenantId)
                .map(Tenant::getMonthlyBudgetUsd)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        writeCache(tenantId, budget);
        return budget;
    }

    private String readCache(Long tenantId) {
        try {
            return redis.opsForValue().get("budget:" + tenantId);
        } catch (DataAccessException ex) {
            log.warn("Budget cache read failed, using database: {}", ex.getMessage());
            return null;
        }
    }

       private void writeCache(Long tenantId, BigDecimal budget) {
        try {
            redis.opsForValue().set("budget:" + tenantId, budget.toPlainString(), BUDGET_CACHE_TTL);
        } catch (DataAccessException ex) {
            log.warn("Budget cache write failed: {}", ex.getMessage());
        }
    }
}