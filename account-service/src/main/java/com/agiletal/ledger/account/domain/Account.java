package com.agiletal.ledger.account.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    private String id;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Account() {}

    public Account(String id, OffsetDateTime createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
