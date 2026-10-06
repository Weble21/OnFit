package com.doggeon.jobrecommendation.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "onfit.seed.enabled", havingValue = "true")
public class JobSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JobSeedRunner.class);
    private final JobSeedService service;

    public JobSeedRunner(JobSeedService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Synthetic job seed complete: {} new postings", service.seed());
    }
}
