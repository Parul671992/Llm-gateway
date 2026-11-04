package com.parul.llmgateway.tenant;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1")
public class AdminController {

    private final ApiKeyService apiKeyService;
    private final BudgetService budgetService;

    public AdminController(ApiKeyService apiKeyService, BudgetService budgetService) {
        this.apiKeyService = apiKeyService;
        this.budgetService = budgetService;
    }

    @PostMapping("/tenants")
    public ResponseEntity<ApiKeyService.CreatedTenant> createTenant(@Valid @RequestBody CreateTenantRequest request) {
        ApiKeyService.CreatedTenant created = apiKeyService.createTenantWithKey(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/tenants/{tenantId}/api-keys")
    public ResponseEntity<ApiKeyService.IssuedKey> createKey(@PathVariable Long tenantId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(apiKeyService.createKeyForTenant(tenantId));
    }

    @PostMapping("/api-keys/{keyId}/revoke")
    public ResponseEntity<Void> revokeKey(@PathVariable Long keyId) {
        apiKeyService.revokeKey(keyId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/tenants/{tenantId}/budget")
    public BudgetResponse setBudget(@PathVariable Long tenantId, @Valid @RequestBody BudgetRequest request) {
        return new BudgetResponse(tenantId, budgetService.setMonthlyBudget(tenantId, request.monthlyBudgetUsd()));
    }

    public record CreateTenantRequest(
            @NotBlank(message = "name must not be empty")
            @Size(max = 100, message = "name must be at most 100 characters")
            String name
    ) {}

    public record BudgetRequest(
            @NotNull(message = "monthlyBudgetUsd is required")
            @DecimalMin(value = "0.00", message = "monthlyBudgetUsd must not be negative")
            @Digits(integer = 8, fraction = 2, message = "monthlyBudgetUsd must have at most 2 decimal places")
            BigDecimal monthlyBudgetUsd
    ) {}

    public record BudgetResponse(Long tenantId, BigDecimal monthlyBudgetUsd) {}
}