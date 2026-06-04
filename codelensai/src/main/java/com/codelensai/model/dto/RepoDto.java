package com.codelensai.model.dto;

import com.codelensai.model.entity.Repository;

import java.time.Instant;

/** Safe repository projection — deliberately omits the encrypted {@code webhook_secret}. */
public record RepoDto(long id, String fullName, boolean active, Long webhookId, Instant createdAt) {
    public static RepoDto from(Repository r) {
        return new RepoDto(
                r.getId(),
                r.getFullName(),
                Boolean.TRUE.equals(r.getActive()),
                r.getWebhookId(),
                r.getCreatedAt());
    }
}
