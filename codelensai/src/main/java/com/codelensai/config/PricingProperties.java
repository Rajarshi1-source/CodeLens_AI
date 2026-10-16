package com.codelensai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Per-model token prices in USD per million tokens ({@code codelens.llm.pricing.models}). Keys with dots
 * or dashes must use bracket notation in YAML ({@code "[claude-opus-5-5]"}). Unknown models cost 0 and log
 * a warning, so a missing row never blocks a review.
 */
@ConfigurationProperties(prefix = "codelens.llm.pricing")
public record PricingProperties(Map<String, Price> models) {

    public PricingProperties {
        models = models == null ? Map.of() : Map.copyOf(models);
    }

    public record Price(BigDecimal inputPerMtok, BigDecimal outputPerMtok) {
        public Price {
            inputPerMtok = inputPerMtok == null ? BigDecimal.ZERO : inputPerMtok;
            outputPerMtok = outputPerMtok == null ? BigDecimal.ZERO : outputPerMtok;
        }
    }
}
