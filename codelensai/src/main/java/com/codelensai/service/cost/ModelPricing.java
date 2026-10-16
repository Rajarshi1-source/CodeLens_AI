package com.codelensai.service.cost;

import com.codelensai.config.PricingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Turns token counts into USD using {@code codelens.llm.pricing.models} (USD per million tokens). */
@Component
public class ModelPricing {

    private static final Logger log = LoggerFactory.getLogger(ModelPricing.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final PricingProperties props;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public ModelPricing(PricingProperties props) {
        this.props = props;
    }

    public BigDecimal cost(String model, long inputTokens, long outputTokens) {
        PricingProperties.Price price = props.models().get(model);
        if (price == null) {
            if (model != null && warned.add(model)) {
                log.warn("No price configured for model '{}' — recording cost 0. Add codelens.llm.pricing.models.\"[{}]\"",
                        model, model);
            }
            return BigDecimal.ZERO;
        }
        return price.inputPerMtok().multiply(BigDecimal.valueOf(inputTokens))
                .add(price.outputPerMtok().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    /** True when the model costs nothing (the local mock): such calls are not charged to the budget. */
    public boolean isFree(String model) {
        PricingProperties.Price price = props.models().get(model);
        return price != null && price.inputPerMtok().signum() == 0 && price.outputPerMtok().signum() == 0;
    }
}
