package com.codelensai.model.dto;

/** Body for {@code POST /api/repos/connect}. {@code githubRepoId} is optional (0 if unknown). */
public record ConnectRepoRequest(String fullName, Long githubRepoId) {
}
