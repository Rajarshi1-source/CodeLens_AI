package com.codelensai.model.dto;

/**
 * The raw unified diff for a PR, served to the frontend diff viewer.
 *
 * <p>{@code diff} is the empty string when the diff is unavailable (no GitHub token, local mock
 * provider, or a fetch failure that degraded to empty) — the client then falls back to its
 * findings-grouped view rather than an empty diff widget.
 */
public record PrDiffResponse(long prId, String headSha, String diff) {
}
