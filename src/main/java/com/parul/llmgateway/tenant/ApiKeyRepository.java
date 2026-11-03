package com.parul.llmgateway.tenant;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {
    Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);
}