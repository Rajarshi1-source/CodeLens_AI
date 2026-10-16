package com.codelensai.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Who may see a repository's PRs, reviews and comments. Access is decided by membership (not by the
 * single {@code repositories.user_id} that connected it), so a second collaborator can be added without
 * re-assigning ownership. The connecting user is the {@code OWNER} and is backfilled by {@code schema.sql}.
 */
@Entity
@Table(name = "repository_members")
@IdClass(RepositoryMember.Key.class)
public class RepositoryMember {

    public static final String OWNER = "OWNER";
    public static final String MEMBER = "MEMBER";

    @Id
    @Column(name = "repo_id")
    private Long repoId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 20)
    private String role = MEMBER;

    @Column(name = "created_at")
    private Instant createdAt;

    public RepositoryMember() {
    }

    public RepositoryMember(Long repoId, Long userId, String role) {
        this.repoId = repoId;
        this.userId = userId;
        this.role = role;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getRepoId() {
        return repoId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Composite primary key {@code (repo_id, user_id)}. */
    public static class Key implements Serializable {
        private Long repoId;
        private Long userId;

        public Key() {
        }

        public Key(Long repoId, Long userId) {
            this.repoId = repoId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(repoId, k.repoId) && Objects.equals(userId, k.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(repoId, userId);
        }
    }
}
