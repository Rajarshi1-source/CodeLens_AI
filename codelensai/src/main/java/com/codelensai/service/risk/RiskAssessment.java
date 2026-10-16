package com.codelensai.service.risk;

import com.codelensai.model.enums.RiskLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Output of {@link RiskScoringService}: an explainable 0-100 score, its band, the reasons that produced it
 * (each with the points it contributed) and a recommended action. Deterministic — the LLM only writes prose.
 */
public record RiskAssessment(int score, RiskLevel level, List<Reason> reasons, String recommendedAction) {

    public RiskAssessment {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public record Reason(String code, String detail, int points) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("detail", detail);
            m.put("points", points);
            return m;
        }
    }

    public List<Map<String, Object>> reasonMaps() {
        return reasons.stream().map(Reason::toMap).toList();
    }
}
