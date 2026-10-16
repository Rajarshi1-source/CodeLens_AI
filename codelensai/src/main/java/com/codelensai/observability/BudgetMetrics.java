package com.codelensai.observability;

import com.codelensai.service.cost.TokenBudgetService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Exposes today's remaining LLM token budget as {@code llm.budget.remaining} (and the limit as
 * {@code llm.budget.limit}) so Grafana can alert before reviews start failing with BUDGET_EXCEEDED.
 * Read from Redis at scrape time; NaN when Redis is unreachable rather than a misleading number.
 */
@Component
public class BudgetMetrics {

    public BudgetMetrics(MeterRegistry registry, TokenBudgetService budget) {
        Gauge.builder("llm.budget.remaining", budget, b -> {
                    try {
                        return b.snapshot().remaining();
                    } catch (RuntimeException e) {
                        return Double.NaN;
                    }
                })
                .description("LLM tokens left in today's (UTC) budget")
                .baseUnit("tokens")
                .register(registry);
        Gauge.builder("llm.budget.limit", budget, b -> {
                    try {
                        return b.snapshot().dailyLimit();
                    } catch (RuntimeException e) {
                        return Double.NaN;
                    }
                })
                .description("Daily LLM token budget")
                .baseUnit("tokens")
                .register(registry);
    }
}
