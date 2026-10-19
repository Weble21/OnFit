package com.donggeon.jobrecommendation.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.donggeon.jobrecommendation.jobanalysis.JobAnalysisRetention;
import com.donggeon.jobrecommendation.recommendation.RecommendationRetention;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "onfit.recommendation.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class RetentionSchedule {
    private static final Logger log = LoggerFactory.getLogger(RetentionSchedule.class);
    private final RecommendationRetention retention;
    private final JobAnalysisRetention analysisRetention;

    public RetentionSchedule(RecommendationRetention retention, JobAnalysisRetention analysisRetention) {
        this.retention = retention;
        this.analysisRetention = analysisRetention;
    }

    @Scheduled(cron = "${onfit.recommendation.cleanup-cron:0 0 3 * * *}", zone = "UTC")
    public void cleanup() {
        log.info("recommendation_cleanup deleted={}", retention.purgeExpired());
        log.info("job_analysis_cleanup deleted={}", analysisRetention.purgeExpired());
    }
}
