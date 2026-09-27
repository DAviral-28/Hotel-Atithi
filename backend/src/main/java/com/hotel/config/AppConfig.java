package com.hotel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class AppConfig {

    /** The hotel's local time zone. Every "today" / check-in / check-out decision is made in IST. */
    public static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * AWS Lambda runs in UTC, which would make "today" lag India by 5h30m (a guest booking at
     * 02:00 IST would be told yesterday is today). Pin the clock to IST instead.
     */
    @Bean
    public Clock clock() {
        return Clock.system(HOTEL_ZONE);
    }
}
