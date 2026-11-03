package com.parul.llmgateway.tenant;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import com.parul.llmgateway.Hashing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "gw_live_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRepository tenantRepository;
    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(TenantRepository tenantRepository, ApiKeyRepository apiKeyRepository) {
        this.tenantRepository = tenantRepository;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional
    public CreatedTenant createTenantWithKey(String tenantName) {
        Tenant tenant = tenantRepository.save(new Tenant(tenantName));

        String plainKey = generateKey();
        String displayPrefix = plainKey.substring(0, 12);
        apiKeyRepository.save(new ApiKey(tenant, Hashing.sha256(plainKey), displayPrefix));

        return new CreatedTenant(tenant.getId(), tenant.getName(), plainKey);
    }

    @Transactional(readOnly = true)
    public Optional<Long> findTenantIdForKey(String plainKey) {
        return apiKeyRepository.findByKeyHashAndRevokedAtIsNull(Hashing.sha256(plainKey))
                .map(apiKey -> apiKey.getTenant().getId());
    }

    private static String generateKey() {
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public record CreatedTenant(Long tenantId, String name, String apiKey) {}
}