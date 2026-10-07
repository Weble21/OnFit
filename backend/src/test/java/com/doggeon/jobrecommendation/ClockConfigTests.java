package com.donggeon.jobrecommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

import com.donggeon.jobrecommendation.config.ClockConfig;

class ClockConfigTests {

    private final ClockConfig config = new ClockConfig();

    @Test
    void todayFollowsKoreanCalendarEvenWhenUtcIsStillYesterday() {
        // 00:30 in Seoul is 15:30 the previous day in UTC.
        Clock seoul = Clock.fixed(Instant.parse("2026-09-28T15:30:00Z"), ZoneId.of("Asia/Seoul"));

        assertThat(LocalDate.now(seoul)).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(config.clock("Asia/Seoul", "").getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void fixedDatePinsToday() {
        Clock clock = config.clock("Asia/Seoul", "2027-12-31");

        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2027, 12, 31));
    }
}
