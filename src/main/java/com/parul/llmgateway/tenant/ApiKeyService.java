package com.parul.llmgateway.tenant;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import com.parul.llmgateway.Hashing;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "gw_live_";
    private static final SecureRandom RANDOM = new SecureRandom();

        private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final TenantRepository tenantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final StringRedisTemplate redis;
    private final Duration keyCacheTtl;

    public ApiKeyService(TenantRepository tenantRepository,
                         ApiKeyRepository apiKeyRepository,
                         StringRedisTemplate redis,
                         @Value("${gateway.auth.key-cache-ttl}") Duration keyCacheTtl) {
        this.tenantRepository = tenantRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.redis = redis;
        this.keyCacheTtl = keyCacheTtl;
    }

    @Transactional
    public CreatedTenant createTenantWithKey(String tenantName) {
        Tenant tenant = tenantRepository.save(new Tenant(tenantName));
        IssuedKey issued = issueKey(tenant);
        return new CreatedTenant(tenant.getId(), tenant.getName(), issued.keyId(), issued.plainKey());
    }

    @Transactional
    public IssuedKey createKeyForTenant(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        return issueKey(tenant);
    }

    @Transactional
    public void revokeKey(Long keyId) {
        ApiKey apiKey = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found"));
        apiKey.revoke();

        // Remove the cached copy, but only AFTER the database change is saved
        String cacheKey = "apikey:" + apiKey.getKeyHash();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictCache(cacheKey);
            }
        });
    }

    private void evictCache(String cacheKey) {
        try {
            redis.delete(cacheKey);
        } catch (DataAccessException ex) {
            log.error("Could not remove revoked key from cache, it stays valid until TTL expires: {}", ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Optional<Long> findTenantIdForKey(String plainKey) {
        String cacheKey = "apikey:" + Hashing.sha256(plainKey);

        // 1. Try Redis first
        String cachedTenantId = readCache(cacheKey);
        if (cachedTenantId != null) {
            return Optional.of(Long.valueOf(cachedTenantId));
        }

        // 2. Miss: ask PostgreSQL, the source of truth
        Optional<Long> tenantId = apiKeyRepository
                .findByKeyHashAndRevokedAtIsNull(Hashing.sha256(plainKey))
                .map(apiKey -> apiKey.getTenant().getId());

        // 3. Only cache VALID keys
        tenantId.ifPresent(id -> writeCache(cacheKey, id.toString()));
        return tenantId;
    }

    private String readCache(String cacheKey) {
        try {
            return redis.opsForValue().get(cacheKey);
        } catch (DataAccessException ex) {
            log.warn("Key cache read failed, checking database: {}", ex.getMessage());
            return null;
        }
    }

    private void writeCache(String cacheKey, String tenantId) {
        try {
            redis.opsForValue().set(cacheKey, tenantId, keyCacheTtl);
        } catch (DataAccessException ex) {
            log.warn("Key cache write failed: {}", ex.getMessage());
        }
    }

    private IssuedKey issueKey(Tenant tenant) {
        String plainKey = generateKey();
        ApiKey saved = apiKeyRepository.save(
                new ApiKey(tenant, Hashing.sha256(plainKey), plainKey.substring(0, 12)));
        return new IssuedKey(saved.getId(), tenant.getId(), plainKey);
    }

    private static String generateKey() {
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public record CreatedTenant(Long tenantId, String name, Long keyId, String apiKey) {}

    public record IssuedKey(Long keyId, Long tenantId, String plainKey) {}
}