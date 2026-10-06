package com.donggeon.jobrecommendation.recommendation;

import java.math.BigDecimal;

/** Explicit weight contract shared by production scoring and offline evaluation. */
public record RecommendationWeights(BigDecimal required, BigDecimal preferred, BigDecimal semantic,
                                    BigDecimal experience, BigDecimal preference) {
    public static final RecommendationWeights DEFAULT = new RecommendationWeights(
            new BigDecimal("0.35"), new BigDecimal("0.20"), new BigDecimal("0.20"),
            new BigDecimal("0.15"), new BigDecimal("0.10"));

    public RecommendationWeights {
        var values = java.util.Arrays.asList(required, preferred, semantic, experience, preference);
        if (values.stream().anyMatch(value -> value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("Weights must be between 0 and 1");
        }
        if (values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("Weights must sum to 1");
        }
    }
}
