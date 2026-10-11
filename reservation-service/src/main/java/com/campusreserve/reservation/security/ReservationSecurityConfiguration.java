package com.campusreserve.reservation.security;

import com.campusreserve.security.CampusJwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class ReservationSecurityConfiguration {

    @Bean
    CampusJwtService campusJwtService(
            @Value("${campus-reserve.security.jwt.hmac-base64-secret:}") String base64Secret,
            @Value("${campus-reserve.security.jwt.issuer:campus-reserve}") String issuer
    ) {
        if (base64Secret.isBlank()) {
            throw new IllegalStateException("CAMPUS_JWT_HMAC_SECRET must be configured");
        }
        return new CampusJwtService(base64Secret, issuer);
    }

    @Bean
    SecurityFilterChain reservationSecurityFilterChain(
            HttpSecurity http,
            ReservationJwtAuthenticationFilter jwtAuthenticationFilter
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
