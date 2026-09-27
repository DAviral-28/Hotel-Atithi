package com.hotel.config;

import com.hotel.ratelimit.RateLimitFilter;
import com.hotel.ratelimit.SecurityHeadersFilter;
import com.hotel.ratelimit.SlidingWindowRateLimiter;
import com.hotel.util.CorsOrigins;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.time.Clock;
import java.util.List;

/**
 * Web-layer protections, registered explicitly so their ORDER is fixed:
 * 1. security headers, 2. CORS allow-list, 3. rate limiting - then Spring MVC.
 * CORS and the headers are servlet filters (not per-controller annotations) so that they also apply
 * to errors and 429 responses; otherwise the browser could not read a 429 and the SPA would show
 * "server unreachable" instead of "try again in N seconds".
 */
@Configuration
public class WebSecurityConfig {

    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> reg = new FilterRegistrationBean<>(new SecurityHeadersFilter());
        reg.setOrder(1);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(
            @Value("${hotel.cors.allowed-origins:" + CorsOrigins.DEFAULT_ALLOWED_ORIGINS + "}") String allowedOrigins
    ) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(CorsOrigins.parse(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Accept", "X-Booking-Token", "X-Staff-Token", "Authorization"));
        config.setExposedHeaders(List.of("Retry-After"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> reg = new FilterRegistrationBean<>(new CorsFilter(source));
        reg.setOrder(2);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            Clock clock,
            @Value("${hotel.security.rate-limit.enabled:true}") boolean enabled,
            @Value("${hotel.security.rate-limit.hold-max:3}") int holdMax,
            @Value("${hotel.security.rate-limit.hold-window-seconds:600}") long holdWindowSeconds,
            @Value("${hotel.security.rate-limit.lookup-max:5}") int lookupMax,
            @Value("${hotel.security.rate-limit.lookup-window-seconds:60}") long lookupWindowSeconds,
            @Value("${hotel.security.rate-limit.staff-login-max:5}") int staffLoginMax,
            @Value("${hotel.security.rate-limit.staff-login-window-seconds:300}") long staffLoginWindowSeconds
    ) {
        RateLimitFilter filter = new RateLimitFilter(
                new SlidingWindowRateLimiter(holdMax, holdWindowSeconds * 1000L, clock::millis), holdWindowSeconds,
                new SlidingWindowRateLimiter(lookupMax, lookupWindowSeconds * 1000L, clock::millis), lookupWindowSeconds,
                new SlidingWindowRateLimiter(staffLoginMax, staffLoginWindowSeconds * 1000L, clock::millis));
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(3);
        reg.setEnabled(enabled);
        return reg;
    }
}
