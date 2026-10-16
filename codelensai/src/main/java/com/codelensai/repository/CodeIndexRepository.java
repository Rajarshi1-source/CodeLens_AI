package com.codelensai.repository;

import com.codelensai.service.embedding.EmbeddingProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JDBC access to the G1 code index ({@code code_chunks} + {@code code_symbols}). Plain SQL rather than JPA
 * so pgvector can be used directly ({@code CAST(:v AS vector)}, {@code <=>} cosine distance) without the
 * hibernate-vector dependency.
 */
@Repository
public class CodeIndexRepository {

    /** Sentinel so {@code NOT IN (:paths)} is valid SQL when there is nothing to exclude. */
    private static final String NONE = "\u0001none\u0001";

    private final JdbcClient jdbc;

    public CodeIndexRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record IndexChunk(String path, String language, String symbol, String kind, int startLine, int endLine,
                             String content, String contentHash, Set<String> defs, Set<String> refs) {
    }

    public record Snippet(long id, String path, String symbol, int startLine, int endLine, String content) {
    }

    @Transactional
    public long upsertChunk(long repoId, IndexChunk c, String refSha, float[] embedding, String provider) {
        long id = jdbc.sql("""
                        INSERT INTO code_chunks(repo_id, path, language, symbol, kind, start_line, end_line, content,
                                                content_hash, ref_sha, embedding, embedding_provider, indexed_at)
                        VALUES (:repoId, :path, :language, :symbol, :kind, :start, :end, :content,
                                :hash, :ref, CAST(:embedding AS vector), :provider, NOW())
                        ON CONFLICT (repo_id, path, content_hash) DO UPDATE SET
                            embedding = EXCLUDED.embedding, embedding_provider = EXCLUDED.embedding_provider,
                            ref_sha = EXCLUDED.ref_sha, start_line = EXCLUDED.start_line, end_line = EXCLUDED.end_line,
                            symbol = EXCLUDED.symbol, kind = EXCLUDED.kind, indexed_at = NOW()
                        RETURNING id""")
                .param("repoId", repoId)
                .param("path", c.path())
                .param("language", c.language())
                .param("symbol", truncate(c.symbol(), 255))
                .param("kind", c.kind())
                .param("start", c.startLine())
                .param("end", c.endLine())
                .param("content", c.content())
                .param("hash", c.contentHash())
                .param("ref", refSha)
                .param("embedding", EmbeddingProvider.toVectorLiteral(embedding))
                .param("provider", provider)
                .query(Long.class)
                .single();
        jdbc.sql("DELETE FROM code_symbols WHERE chunk_id = :id").param("id", id).update();
        insertSymbols(id, repoId, c.defs(), "DEF");
        insertSymbols(id, repoId, c.refs(), "REF");
        return id;
    }

    private void insertSymbols(long chunkId, long repoId, Set<String> symbols, String role) {
        for (String s : symbols) {
            if (s == null || s.isBlank() || s.length() > 255) {
                continue;
            }
            jdbc.sql("""
                            INSERT INTO code_symbols(chunk_id, repo_id, symbol, role) VALUES (:c, :r, :s, :role)
                            ON CONFLICT DO NOTHING""")
                    .param("c", chunkId).param("r", repoId).param("s", s).param("role", role)
                    .update();
        }
    }

    /** Delete a path's chunks whose content hash is not in {@code keep} (all of them when {@code keep} is empty). */
    public int deleteStale(long repoId, String path, Collection<String> keep) {
        return jdbc.sql("DELETE FROM code_chunks WHERE repo_id = :r AND path = :p AND content_hash NOT IN (:keep)")
                .param("r", repoId).param("p", path).param("keep", withSentinel(keep))
                .update();
    }

    /** Full re-index: drop chunks for files that no longer exist. */
    public int deletePathsNotIn(long repoId, Collection<String> paths) {
        return jdbc.sql("DELETE FROM code_chunks WHERE repo_id = :r AND path NOT IN (:paths)")
                .param("r", repoId).param("paths", withSentinel(paths))
                .update();
    }

    public List<Snippet> nearest(long repoId, String provider, float[] v, int k, Collection<String> excludePaths) {
        return jdbc.sql("""
                        SELECT id, path, symbol, start_line, end_line, content FROM code_chunks
                        WHERE repo_id = :r AND embedding_provider = :p AND path NOT IN (:ex)
                        ORDER BY embedding <=> CAST(:v AS vector) LIMIT :k""")
                .param("r", repoId).param("p", provider).param("ex", withSentinel(excludePaths))
                .param("v", EmbeddingProvider.toVectorLiteral(v)).param("k", k)
                .query(CodeIndexRepository::snippet).list();
    }

    /** Chunks that reference (call) any of {@code symbols}, outside the changed files. */
    public List<Snippet> callers(long repoId, Collection<String> symbols, Collection<String> excludePaths, int limit) {
        if (symbols.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT DISTINCT c.id, c.path, c.symbol, c.start_line, c.end_line, c.content
                        FROM code_chunks c JOIN code_symbols s ON s.chunk_id = c.id
                        WHERE s.repo_id = :r AND s.role = 'REF' AND s.symbol IN (:symbols) AND c.path NOT IN (:ex)
                        ORDER BY c.path, c.start_line LIMIT :limit""")
                .param("r", repoId).param("symbols", symbols).param("ex", withSentinel(excludePaths))
                .param("limit", limit)
                .query(CodeIndexRepository::snippet).list();
    }

    /** How many distinct chunks reference each symbol (the "fan-in" used by the risk score). */
    public Map<String, Integer> callerCounts(long repoId, Collection<String> symbols, Collection<String> excludePaths) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (symbols.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT s.symbol, COUNT(DISTINCT s.chunk_id) AS n
                        FROM code_symbols s JOIN code_chunks c ON c.id = s.chunk_id
                        WHERE s.repo_id = :r AND s.role = 'REF' AND s.symbol IN (:symbols) AND c.path NOT IN (:ex)
                        GROUP BY s.symbol""")
                .param("r", repoId).param("symbols", symbols).param("ex", withSentinel(excludePaths))
                .query((rs, n) -> Map.entry(rs.getString("symbol"), rs.getInt("n")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Test files whose path mentions one of {@code baseNames}. */
    public List<Snippet> tests(long repoId, Collection<String> baseNames, Collection<String> excludePaths, int limit) {
        if (baseNames.isEmpty()) {
            return List.of();
        }
        List<String> patterns = new ArrayList<>();
        baseNames.forEach(b -> patterns.add("%" + b.toLowerCase() + "%"));
        return jdbc.sql("""
                        SELECT id, path, symbol, start_line, end_line, content FROM code_chunks
                        WHERE repo_id = :r AND path ~* '(^|/)(test|tests|spec|__tests__)/|(test|spec|tests)\\.[a-z]+$|_test\\.'
                          AND lower(path) LIKE ANY (CAST(:patterns AS text[])) AND path NOT IN (:ex)
                        ORDER BY path, start_line LIMIT :limit""")
                .param("r", repoId)
                .param("patterns", "{" + String.join(",", patterns.stream().map(CodeIndexRepository::arrayElement).toList()) + "}")
                .param("ex", withSentinel(excludePaths)).param("limit", limit)
                .query(CodeIndexRepository::snippet).list();
    }

    public int countChunks(long repoId) {
        return jdbc.sql("SELECT COUNT(*) FROM code_chunks WHERE repo_id = :r").param("r", repoId)
                .query(Integer.class).single();
    }

    private static Snippet snippet(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Snippet(rs.getLong("id"), rs.getString("path"), rs.getString("symbol"),
                rs.getInt("start_line"), rs.getInt("end_line"), rs.getString("content"));
    }

    private static Collection<String> withSentinel(Collection<String> values) {
        List<String> out = new ArrayList<>(values == null ? List.of() : values);
        out.add(NONE);
        return out;
    }

    private static String arrayElement(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
