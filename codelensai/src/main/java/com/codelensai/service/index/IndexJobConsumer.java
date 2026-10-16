package com.codelensai.service.index;

import com.codelensai.config.IndexingProperties;
import com.codelensai.util.StreamKeys;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Consumes {@code index-jobs} with a consumer group — the same at-least-once + ack-after-success +
 * reclaim/dead-letter shape as {@code ReviewJobConsumer}. Indexing is idempotent (chunks upsert on
 * {@code (repo_id, path, content_hash)}), so redelivery is harmless.
 */
@Service
public class IndexJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(IndexJobConsumer.class);
    private static final int MAX_RETRIES = 3;
    private static final Duration BLOCK = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final RepoIndexService indexService;
    private final IndexingProperties props;
    private final String consumerName = "indexer-" + System.getenv().getOrDefault("HOSTNAME", "1");
    private final ExecutorService loop = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "index-job-consumer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    public IndexJobConsumer(StringRedisTemplate redis, RepoIndexService indexService, IndexingProperties props) {
        this.redis = redis;
        this.indexService = indexService;
        this.props = props;
    }

    @PostConstruct
    void start() {
        if (!Boolean.TRUE.equals(props.enabled())) {
            log.info("Repository indexing disabled (codelens.indexing.enabled=false)");
            return;
        }
        try {
            redis.opsForStream().createGroup(StreamKeys.INDEX_JOBS, ReadOffset.latest(), StreamKeys.INDEX_GROUP);
        } catch (Exception e) {
            log.debug("Index consumer group already present: {}", e.getMessage());
        }
        loop.execute(this::runLoop);
    }

    @PreDestroy
    void stop() {
        running = false;
        loop.shutdownNow();
    }

    private void runLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                poll();
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.warn("Index consumer poll error: {}", e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    void poll() {
        for (PendingMessage pm : redis.opsForStream().pending(StreamKeys.INDEX_JOBS,
                Consumer.from(StreamKeys.INDEX_GROUP, consumerName), Range.unbounded(), 20L)) {
            List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                    StreamKeys.INDEX_JOBS, StreamKeys.INDEX_GROUP, consumerName, Duration.ZERO, pm.getId());
            if (claimed.isEmpty()) {
                continue;
            }
            if (pm.getTotalDeliveryCount() >= MAX_RETRIES) {
                redis.opsForStream().add(StreamRecords.mapBacked(toStringMap(claimed.get(0).getValue()))
                        .withStreamKey(StreamKeys.INDEX_DLQ));
                ack(claimed.get(0));
                log.error("Index job {} dead-lettered after {} attempts", pm.getId(), pm.getTotalDeliveryCount());
            } else {
                process(claimed.get(0));
            }
        }
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(StreamKeys.INDEX_GROUP, consumerName),
                StreamReadOptions.empty().count(1).block(BLOCK),
                StreamOffset.create(StreamKeys.INDEX_JOBS, ReadOffset.lastConsumed()));
        if (records != null) {
            records.forEach(this::process);
        }
    }

    private void process(MapRecord<String, Object, Object> record) {
        Map<Object, Object> body = record.getValue();
        try {
            long repoId = Long.parseLong(String.valueOf(body.get("repoId")));
            IndexQueueService.Mode mode = IndexQueueService.Mode.valueOf(String.valueOf(body.get("mode")));
            switch (mode) {
                case FULL -> indexService.indexFull(repoId);
                case PATHS -> indexService.indexPaths(repoId, String.valueOf(body.get("ref")),
                        Arrays.stream(String.valueOf(body.get("paths")).split("\n")).filter(p -> !p.isBlank()).toList());
            }
            ack(record);
        } catch (IllegalArgumentException malformed) {
            log.error("Malformed index job {} acknowledged and dropped: {}", record.getId(), malformed.getMessage());
            ack(record);
        } catch (Exception e) {
            log.warn("Index job {} failed (will retry): {}", record.getId(), e.getMessage());
        }
    }

    private void ack(MapRecord<String, Object, Object> record) {
        redis.opsForStream().acknowledge(StreamKeys.INDEX_JOBS, StreamKeys.INDEX_GROUP, record.getId());
    }

    private static Map<String, String> toStringMap(Map<Object, Object> body) {
        return body.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue())));
    }
}
