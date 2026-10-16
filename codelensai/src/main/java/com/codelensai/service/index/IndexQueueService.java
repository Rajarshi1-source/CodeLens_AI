package com.codelensai.service.index;

import com.codelensai.util.StreamKeys;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;

/** Enqueues repository indexing jobs on the {@code index-jobs} Redis Stream (async, like reviews). */
@Service
public class IndexQueueService {

    public enum Mode { FULL, PATHS }

    private final StringRedisTemplate redis;

    public IndexQueueService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void enqueueFull(long repoId) {
        enqueue(repoId, Mode.FULL, "", "");
    }

    public void enqueuePaths(long repoId, String ref, Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        enqueue(repoId, Mode.PATHS, ref == null ? "" : ref, String.join("\n", paths));
    }

    private void enqueue(long repoId, Mode mode, String ref, String paths) {
        redis.opsForStream().add(StreamRecords.mapBacked(Map.of(
                        "repoId", String.valueOf(repoId), "mode", mode.name(), "ref", ref, "paths", paths))
                .withStreamKey(StreamKeys.INDEX_JOBS));
    }
}
