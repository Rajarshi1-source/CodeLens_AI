package com.codelensai.controller;

import com.codelensai.model.dto.ReviewSummaryResponse;
import com.codelensai.model.entity.User;
import com.codelensai.security.AccessGuard;
import com.codelensai.security.CurrentUser;
import com.codelensai.service.ReviewQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read surface for a single review session (membership-checked). */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewQueryService reviewQueries;
    private final AccessGuard guard;

    public ReviewController(ReviewQueryService reviewQueries, AccessGuard guard) {
        this.reviewQueries = reviewQueries;
        this.guard = guard;
    }

    @GetMapping("/{id}/summary")
    public ReviewSummaryResponse summary(@CurrentUser User user, @PathVariable long id) {
        return reviewQueries.summaryOf(guard.requireSession(id, user.getId()));
    }
}
