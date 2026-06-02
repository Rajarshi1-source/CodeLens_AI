package com.codelensai.model.dto;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.enums.ReviewStatus;

public record PullRequestDto(
        long id,
        int number,
        String title,
        String author,
        ReviewStatus status,
        int filesChanged,
        int additions,
        int deletions,
        String htmlUrl
) {
    public static PullRequestDto from(PullRequest pr) {
        return new PullRequestDto(
                pr.getId(),
                pr.getPrNumber() == null ? 0 : pr.getPrNumber(),
                pr.getTitle(),
                pr.getAuthor(),
                pr.getStatus(),
                pr.getFilesChanged() == null ? 0 : pr.getFilesChanged(),
                pr.getAdditions() == null ? 0 : pr.getAdditions(),
                pr.getDeletions() == null ? 0 : pr.getDeletions(),
                pr.getHtmlUrl());
    }
}
