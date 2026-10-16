package com.codelensai.security;

import com.codelensai.model.entity.PullRequest;
import com.codelensai.model.entity.ReviewCommentEntity;
import com.codelensai.model.entity.ReviewSession;
import com.codelensai.repository.PullRequestRepository;
import com.codelensai.repository.RepositoryMemberRepository;
import com.codelensai.repository.ReviewCommentRepository;
import com.codelensai.repository.ReviewSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Membership-based authorization for PRs, review sessions and comments: a user may act on a resource only
 * if they are a member of its repository ({@code repository_members}). Denial is a <b>404</b>, not a 403,
 * so resource ids cannot be enumerated. Used by the REST controllers and the STOMP interceptor alike.
 */
@Component
public class AccessGuard {

    private final PullRequestRepository pullRequests;
    private final ReviewSessionRepository sessions;
    private final ReviewCommentRepository comments;
    private final RepositoryMemberRepository members;

    public AccessGuard(PullRequestRepository pullRequests, ReviewSessionRepository sessions,
                       ReviewCommentRepository comments, RepositoryMemberRepository members) {
        this.pullRequests = pullRequests;
        this.sessions = sessions;
        this.comments = comments;
        this.members = members;
    }

    public PullRequest requirePr(long prId, long userId) {
        return pullRequests.findAccessible(prId, userId).orElseThrow(AccessGuard::notFound);
    }

    public ReviewSession requireSession(long sessionId, long userId) {
        ReviewSession s = sessions.findById(sessionId).orElseThrow(AccessGuard::notFound);
        requirePr(s.getPrId(), userId);
        return s;
    }

    public ReviewCommentEntity requireComment(long commentId, long userId) {
        ReviewCommentEntity c = comments.findById(commentId).orElseThrow(AccessGuard::notFound);
        requirePr(c.getPrId(), userId);
        return c;
    }

    public boolean canAccessPr(long prId, long userId) {
        return pullRequests.findAccessible(prId, userId).isPresent();
    }

    public boolean isMember(long repoId, long userId) {
        return members.existsByRepoIdAndUserId(repoId, userId);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
