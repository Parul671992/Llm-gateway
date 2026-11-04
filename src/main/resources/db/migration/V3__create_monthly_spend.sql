CREATE TABLE tenant_monthly_spend (
    tenant_id     BIGINT      NOT NULL REFERENCES tenants (id),
    month         VARCHAR(7)  NOT NULL,
    spent_micros  BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, month)
);