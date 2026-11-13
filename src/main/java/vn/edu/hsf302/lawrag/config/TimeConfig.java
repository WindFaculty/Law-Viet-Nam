package vn.edu.hsf302.lawrag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * All quota periods, consent deadlines and applicable dates use the Vietnamese
 * business day. Tests may override this bean with a fixed clock.
 */
@Configuration
public class TimeConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Bean
    Clock appClock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
