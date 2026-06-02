package com.codelensai.controller;

import com.codelensai.model.dto.PullRequestDto;
import com.codelensai.model.dto.ReviewCommentDto;
import com.codelensai.model.entity.PullRequest;
import com.codelensai.repository.ReviewCommentRepository;
import com.codelensai.service.PullRequestService;
import com.codelensai.service.ReviewQueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/prs")
public class PullRequestController {

    private final PullRequestService pullRequestService;
    private final ReviewCommentRepository reviewCommentRepository;
    private final ReviewQueueService reviewQueueService;

    public PullRequestController(PullRequestService pullRequestService,
                                 ReviewCommentRepository reviewCommentRepository,
                                 ReviewQueueService reviewQueueService) {
        this.pullRequestService = pullRequestService;
        this.reviewCommentRepository = reviewCommentRepository;
        this.reviewQueueService = reviewQueueService;
    }

    @GetMapping
    public List<PullRequestDto> list() {
        return pullRequestService.listRecent().stream().map(PullRequestDto::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PullRequestDto> get(@PathVariable long id) {
        return pullRequestService.find(id)
                .map(PullRequestDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/comments")
    public List<ReviewCommentDto> comments(@PathVariable long id) {
        return reviewCommentRepository.findByPrIdOrderByFilePathAscLineNumberAsc(id).stream()
                .map(ReviewCommentDto::from)
                .toList();
    }

    @PostMapping("/{id}/re-review")
    public ResponseEntity<String> reReview(@PathVariable long id) {
        PullRequest pr = pullRequestService.find(id).orElse(null);
        if (pr == null) {
            return ResponseEntity.notFound().build();
        }
        reviewQueueService.enqueue(pr.getId(), pr.getHeadSha());
        return ResponseEntity.accepted().body("re-review queued");
    }
}
