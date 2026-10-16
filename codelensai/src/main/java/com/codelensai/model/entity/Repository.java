package com.codelensai.model.entity;

import com.codelensai.security.CryptoStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "repositories",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "github_repo_id"}))
public class Repository {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "github_repo_id", nullable = false)
    private Long githubRepoId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "webhook_id")
    private Long webhookId;

    /** Encrypted at rest (AES-GCM). Never expose through a DTO or toString(). */
    @Convert(converter = CryptoStringConverter.class)
    @Column(name = "webhook_secret", length = 512)
    private String webhookSecret;

    @Column(name = "is_active")
    private Boolean active = Boolean.TRUE;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "default_branch")
    private String defaultBranch;

    @Column(name = "index_status", length = 20)
    private String indexStatus = "NOT_INDEXED";

    @Column(name = "indexed_sha", length = 40)
    private String indexedSha;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    @Column(name = "indexed_chunks")
    private Integer indexedChunks = 0;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getGithubRepoId() {
        return githubRepoId;
    }

    public void setGithubRepoId(Long githubRepoId) {
        this.githubRepoId = githubRepoId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Long getWebhookId() {
        return webhookId;
    }

    public void setWebhookId(Long webhookId) {
        this.webhookId = webhookId;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getIndexStatus() {
        return indexStatus;
    }

    public void setIndexStatus(String indexStatus) {
        this.indexStatus = indexStatus;
    }

    public String getIndexedSha() {
        return indexedSha;
    }

    public void setIndexedSha(String indexedSha) {
        this.indexedSha = indexedSha;
    }

    public Instant getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(Instant indexedAt) {
        this.indexedAt = indexedAt;
    }

    public Integer getIndexedChunks() {
        return indexedChunks;
    }

    public void setIndexedChunks(Integer indexedChunks) {
        this.indexedChunks = indexedChunks;
    }

    @Override
    public String toString() {
        return "Repository{id=" + id + ", fullName='" + fullName + "'}";
    }
}
