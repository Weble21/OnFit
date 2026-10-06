package com.doggeon.jobrecommendation.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /**
     * Deadlines are Korean calendar dates, so "today" follows the service time zone, not the server's.
     * A fixed date keeps tests and demos stable after the synthetic postings' deadlines pass.
     */
    @Bean
    public Clock clock(@Value("${onfit.time-zone:Asia/Seoul}") String timeZone,
                @Value("${onfit.fixed-date:}") String fixedDate) {
        ZoneId zone = ZoneId.of(timeZone);
        if (fixedDate.isBlank()) {
            return Clock.system(zone);
        }
        return Clock.fixed(LocalDate.parse(fixedDate).atStartOfDay(zone).toInstant(), zone);
    }
}
