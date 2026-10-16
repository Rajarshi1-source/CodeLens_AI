package com.codelensai.service.feedback;

import com.codelensai.model.entity.ReviewCommentEntity;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thumbs up/down per AI comment (the feedback flywheel, master plan §18.5). One vote per (comment, user),
 * upserted. Votes are stored with the comment's repo, rule key and fingerprint so they (a) drive per-repo
 * suppression of consistently-dismissed rules (G7) and (b) survive a same-SHA re-review, which replaces
 * the comment rows — the persistence layer re-links them by fingerprint.
 */
@Service
public class FeedbackService {

    private final JdbcClient jdbc;
    private final MeterRegistry meters;

    public FeedbackService(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    public record Summary(long up, long down, Integer myVote) {
        public static final Summary NONE = new Summary(0, 0, null);
    }

    @Transactional
    public Summary vote(ReviewCommentEntity comment, Long repoId, long userId, int vote) {
        if (vote != 1 && vote != -1) {
            throw new IllegalArgumentException("vote must be -1 or 1");
        }
        jdbc.sql("""
                        INSERT INTO comment_feedback(comment_id, user_id, vote, repo_id, rule_key, fingerprint, created_at, updated_at)
                        VALUES (:c, :u, :v, :repo, :rule, :fp, NOW(), NOW())
                        ON CONFLICT (comment_id, user_id) DO UPDATE SET vote = EXCLUDED.vote, updated_at = NOW()""")
                .param("c", comment.getId()).param("u", userId).param("v", (short) vote)
                .param("repo", repoId).param("rule", comment.getRuleKey()).param("fp", comment.getFingerprint())
                .update();
        meters.counter("feedback.votes", "vote", vote > 0 ? "up" : "down").increment();
        return summaries(List.of(comment.getId()), userId).getOrDefault(comment.getId(), Summary.NONE);
    }

    @Transactional
    public Summary clear(long commentId, long userId) {
        jdbc.sql("DELETE FROM comment_feedback WHERE comment_id = :c AND user_id = :u")
                .param("c", commentId).param("u", userId).update();
        return summaries(List.of(commentId), userId).getOrDefault(commentId, Summary.NONE);
    }

    /** Up/down counts plus the caller's own vote, for a page of comments in one query. */
    public Map<Long, Summary> summaries(Collection<Long> commentIds, Long userId) {
        Map<Long, Summary> out = new HashMap<>();
        if (commentIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT comment_id,
                               SUM(CASE WHEN vote > 0 THEN 1 ELSE 0 END) AS up,
                               SUM(CASE WHEN vote < 0 THEN 1 ELSE 0 END) AS down,
                               MAX(CASE WHEN user_id = :u THEN vote END) AS mine
                        FROM comment_feedback WHERE comment_id IN (:ids) GROUP BY comment_id""")
                .param("u", userId == null ? -1L : userId).param("ids", commentIds)
                .query((rs, n) -> {
                    int mine = rs.getInt("mine");
                    boolean hasMine = !rs.wasNull();
                    return Map.entry(rs.getLong("comment_id"),
                            new Summary(rs.getLong("up"), rs.getLong("down"), hasMine ? mine : null));
                })
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
