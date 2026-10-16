package com.codelensai.service.cost;

import com.codelensai.config.BudgetProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Daily LLM token budget, enforced atomically in Redis across pods (master plan §13 "cost runaway").
 *
 * <p>A call first {@link #reserve reserves} its estimated tokens: a Lua script INCRBYs the UTC-day counter
 * and rolls the increment back if it would cross the limit, so 200 concurrent reservations can never
 * overshoot. When the real usage arrives the reservation is {@link #reconcile reconciled} (the delta may
 * be negative); a call that fails before reporting usage is {@link #release released}.
 */
@Service
public class TokenBudgetService {

    private static final String KEY_PREFIX = "rate:llm:tokens:";
    private static final long KEY_TTL_SECONDS = 172_800; // 2 days

    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local v = redis.call('INCRBY', KEYS[1], ARGV[1])
            if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            if v > tonumber(ARGV[2]) then
              redis.call('DECRBY', KEYS[1], ARGV[1])
              return -1
            end
            return v
            """, Long.class);

    private static final DefaultRedisScript<Long> ADJUST = new DefaultRedisScript<>("""
            local v = redis.call('INCRBY', KEYS[1], ARGV[1])
            if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            if v < 0 then redis.call('SET', KEYS[1], 0, 'KEEPTTL') return 0 end
            return v
            """, Long.class);

    private final StringRedisTemplate redis;
    private final BudgetProperties props;

    public TokenBudgetService(StringRedisTemplate redis, BudgetProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** A held reservation; {@code charged=false} for free models (nothing to reconcile). */
    public record Reservation(String key, long tokens, boolean charged) {
        static Reservation free() {
            return new Reservation(null, 0, false);
        }
    }

    public record Snapshot(long dailyLimit, long usedToday, long remaining) {
    }

    /** Reserve {@code estimate} tokens or throw {@link BudgetExceededException}. */
    public Reservation reserve(long estimate, boolean freeModel) {
        if (freeModel && !Boolean.TRUE.equals(props.enforceForFreeModels())) {
            return Reservation.free();
        }
        long tokens = Math.max(1, estimate);
        String key = todayKey();
        Long result = redis.execute(RESERVE, List.of(key),
                String.valueOf(tokens), String.valueOf(props.dailyTokens()), String.valueOf(KEY_TTL_SECONDS));
        if (result == null || result < 0) {
            throw new BudgetExceededException(tokens, usedToday(), props.dailyTokens());
        }
        return new Reservation(key, tokens, true);
    }

    /** Replace the reservation with the actual usage (delta may be negative). */
    public void reconcile(Reservation reservation, long actualTokens) {
        if (reservation == null || !reservation.charged()) {
            return;
        }
        long delta = Math.max(0, actualTokens) - reservation.tokens();
        if (delta != 0) {
            redis.execute(ADJUST, List.of(reservation.key()), String.valueOf(delta), String.valueOf(KEY_TTL_SECONDS));
        }
    }

    /** The call failed before reporting usage: give the reservation back. */
    public void release(Reservation reservation) {
        if (reservation == null || !reservation.charged()) {
            return;
        }
        redis.execute(ADJUST, List.of(reservation.key()), String.valueOf(-reservation.tokens()), String.valueOf(KEY_TTL_SECONDS));
    }

    public long usedToday() {
        String v = redis.opsForValue().get(todayKey());
        try {
            return v == null ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public Snapshot snapshot() {
        long used = usedToday();
        return new Snapshot(props.dailyTokens(), used, Math.max(0, props.dailyTokens() - used));
    }

    static String todayKey() {
        return KEY_PREFIX + LocalDate.now(ZoneOffset.UTC);
    }
}
