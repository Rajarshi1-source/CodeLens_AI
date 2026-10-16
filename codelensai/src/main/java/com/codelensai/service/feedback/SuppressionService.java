package com.codelensai.service.feedback;

import com.codelensai.config.FeedbackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * G7 "learning from dismissals": a rule ({@code rule_key}) that a repository's reviewers consistently
 * thumbs-down — at least {@code min-votes} votes with a dismiss rate of {@code dismiss-rate} or more — is
 * suppressed for that repository's future reviews. Cached per repo for five minutes.
 */
@Service
public class SuppressionService {

    private static final Logger log = LoggerFactory.getLogger(SuppressionService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private record Cached(Set<String> keys, Instant at) {
    }

    private final JdbcClient jdbc;
    private final FeedbackProperties props;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public SuppressionService(JdbcClient jdbc, FeedbackProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public Set<String> suppressedRuleKeys(Long repoId) {
        if (repoId == null || !Boolean.TRUE.equals(props.suppressionEnabled())) {
            return Set.of();
        }
        Cached c = cache.get(repoId);
        if (c != null && c.at().plus(CACHE_TTL).isAfter(Instant.now())) {
            return c.keys();
        }
        Set<String> keys = new HashSet<>(jdbc.sql("""
                        SELECT rule_key FROM comment_feedback
                        WHERE repo_id = :r AND rule_key IS NOT NULL
                        GROUP BY rule_key
                        HAVING COUNT(*) >= :min AND AVG(CASE WHEN vote < 0 THEN 1.0 ELSE 0.0 END) >= :rate""")
                .param("r", repoId).param("min", props.minVotes()).param("rate", props.dismissRate())
                .query(String.class).list());
        if (!keys.isEmpty()) {
            log.info("Suppressing consistently-dismissed rules for repo {}: {}", repoId, keys);
        }
        Set<String> frozen = Set.copyOf(keys);
        cache.put(repoId, new Cached(frozen, Instant.now()));
        return frozen;
    }

    public void invalidate(Long repoId) {
        if (repoId != null) {
            cache.remove(repoId);
        }
    }
}
