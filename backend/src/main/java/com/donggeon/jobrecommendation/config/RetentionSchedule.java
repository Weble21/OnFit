package com.doggeon.jobrecommendation.config;

import com.doggeon.jobrecommendation.recommendation.RecommendationRetention;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "onfit.recommendation.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class RetentionSchedule {
    private static final Logger log = LoggerFactory.getLogger(RetentionSchedule.class);
    private final RecommendationRetention retention;

    public RetentionSchedule(RecommendationRetention retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "${onfit.recommendation.cleanup-cron:0 0 3 * * *}", zone = "UTC")
    public void cleanup() {
        log.info("recommendation_cleanup deleted={}", retention.purgeExpired());
    }
}
