package com.codelensai.service.feedback;

import com.codelensai.util.DiffParser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Closes the feedback flywheel (G7): comments that reviewers repeatedly dismissed become candidate
 * <i>negative</i> eval cases — the hunk they were posted on, with {@code must_not_flag} at that anchor — so a
 * prompt change that keeps producing the same false positive fails the eval. A human curates them into
 * {@code eval/testset.jsonl} (see {@code eval/export_negatives.py}).
 */
@Service
public class FeedbackExportService {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    private final JdbcClient jdbc;
    private final DiffParser diffParser;
    private final ObjectMapper objectMapper;

    public FeedbackExportService(JdbcClient jdbc, DiffParser diffParser, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.diffParser = diffParser;
        this.objectMapper = objectMapper;
    }

    private record Dismissed(String fingerprint, String file, int line, String ruleKey, String diff) {
    }

    public String exportNdjson(Long repoId, int minDismissals) {
        List<Dismissed> rows = jdbc.sql("""
                        SELECT c.fingerprint, c.file_path, c.line_number, c.rule_key, d.diff
                        FROM review_comments c
                        JOIN review_sessions s ON s.id = c.session_id
                        JOIN pull_requests p ON p.id = c.pr_id
                        JOIN pr_diffs d ON d.pr_id = c.pr_id AND d.head_sha = s.head_sha
                        JOIN comment_feedback f ON f.comment_id = c.id
                        WHERE (CAST(:repo AS BIGINT) IS NULL OR p.repo_id = CAST(:repo AS BIGINT))
                        GROUP BY c.id, c.fingerprint, c.file_path, c.line_number, c.rule_key, d.diff
                        HAVING SUM(CASE WHEN f.vote < 0 THEN 1 ELSE 0 END) >= :min""")
                .param("repo", repoId).param("min", minDismissals)
                .query((rs, n) -> new Dismissed(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5)))
                .list();
        StringBuilder out = new StringBuilder();
        for (Dismissed d : rows) {
            String hunk = hunkContaining(d.diff(), d.file(), d.line());
            if (hunk == null) {
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", "neg-" + d.fingerprint());
            c.put("category", "feedback-negative");
            c.put("language", diffParser.detectLanguage(d.file()));
            c.put("diff", "--- a/" + d.file() + "\n+++ b/" + d.file() + "\n" + hunk);
            c.put("expected_findings", List.of());
            c.put("must_not_flag", List.of(Map.of("file", d.file(), "line", d.line(), "rule_key", String.valueOf(d.ruleKey()))));
            out.append(objectMapper.writeValueAsString(c)).append('\n');
        }
        return out.toString();
    }

    private String hunkContaining(String diff, String file, int line) {
        for (DiffParser.FileDiff f : diffParser.splitByFile(diff)) {
            if (!f.path().equals(file)) {
                continue;
            }
            for (DiffParser.Hunk h : f.hunks()) {
                int cur = h.newStartLine();
                for (String raw : h.text().split("\n", -1)) {
                    Matcher m = HUNK_HEADER.matcher(raw);
                    if (m.matches()) {
                        cur = Integer.parseInt(m.group(1));
                        continue;
                    }
                    if (raw.startsWith("-")) {
                        continue;
                    }
                    if (cur == line) {
                        return h.text();
                    }
                    cur++;
                }
            }
        }
        return null;
    }
}
