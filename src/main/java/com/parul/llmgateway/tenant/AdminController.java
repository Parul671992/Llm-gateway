package com.parul.llmgateway.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
@RequestMapping("/admin/v1")
public class AdminController {

    private final ApiKeyService apiKeyService;
    private final BudgetService budgetService;
    private final byte[] adminToken;

    public AdminController(ApiKeyService apiKeyService,
                           BudgetService budgetService,
                           @Value("${gateway.admin.token}") String adminToken) {
        this.apiKeyService = apiKeyService;
        this.budgetService = budgetService;
        this.adminToken = adminToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/tenants")
    public ResponseEntity<ApiKeyService.CreatedTenant> createTenant(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @Valid @RequestBody CreateTenantRequest request) {
        checkAdminToken(token);
        ApiKeyService.CreatedTenant created = apiKeyService.createTenantWithKey(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    private void checkAdminToken(String token) {
        if (token == null || !MessageDigest.isEqual(adminToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid admin token");
        }
    }

    public record CreateTenantRequest(
            @NotBlank(message = "name must not be empty")
            @Size(max = 100, message = "name must be at most 100 characters")
            String name
    ) {}

        @PostMapping("/tenants/{tenantId}/api-keys")
    public ResponseEntity<ApiKeyService.IssuedKey> createKey(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long tenantId) {
        checkAdminToken(token);
        return ResponseEntity.status(HttpStatus.CREATED).body(apiKeyService.createKeyForTenant(tenantId));
    }

    @PostMapping("/api-keys/{keyId}/revoke")
    public ResponseEntity<Void> revokeKey(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long keyId) {
        checkAdminToken(token);
        apiKeyService.revokeKey(keyId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/tenants/{tenantId}/budget")
    public BudgetResponse setBudget(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable Long tenantId,
            @Valid @RequestBody BudgetRequest request) {
        checkAdminToken(token);
        return new BudgetResponse(tenantId, budgetService.setMonthlyBudget(tenantId, request.monthlyBudgetUsd()));
    }

    public record BudgetRequest(
            @NotNull(message = "monthlyBudgetUsd is required")
            @DecimalMin(value = "0.00", message = "monthlyBudgetUsd must not be negative")
            @Digits(integer = 8, fraction = 2, message = "monthlyBudgetUsd must have at most 2 decimal places")
            BigDecimal monthlyBudgetUsd
    ) {}

    public record BudgetResponse(Long tenantId, BigDecimal monthlyBudgetUsd) {}
}