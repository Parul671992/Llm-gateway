package com.parul.llmgateway.tenant;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected ApiKey() {
    }

    public ApiKey(Tenant tenant, String keyHash, String keyPrefix) {
        this.tenant = tenant;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
    }

    public Long getId() { return id; }
    public Tenant getTenant() { return tenant; }
    public String getKeyPrefix() { return keyPrefix; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
}