package com.parul.llmgateway.tenant;

public class BudgetExceededException extends RuntimeException {
    public BudgetExceededException(Long tenantId) {
        super("Monthly budget exceeded for tenant " + tenantId);
    }
}