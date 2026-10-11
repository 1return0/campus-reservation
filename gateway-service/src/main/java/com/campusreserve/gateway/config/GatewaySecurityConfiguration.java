package com.campusreserve.gateway.config;

import com.campusreserve.security.CampusJwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewaySecurityConfiguration {

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
}
