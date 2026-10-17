package com.campusreserve.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRouteConfiguration {

    @Bean
    RouteLocator campusReserveRoutes(
            RouteLocatorBuilder builder,
            @Value("${campus-reserve.gateway.reservation-uri:lb://reservation-service}") String reservationUri,
            @Value("${campus-reserve.gateway.identity-uri:lb://identity-service}") String identityUri,
            @Value("${campus-reserve.gateway.notification-uri:lb://notification-service}") String notificationUri
    ) {
        return builder.routes()
                .route("identity-service-api", route -> route
                        .path("/api/v1/auth/**")
                        .uri(identityUri))
                .route("identity-service-admin-api", route -> route
                        .path("/api/v1/admin/identity/**")
                        .uri(identityUri))
                .route("notification-service-api", route -> route
                        .path("/api/v1/notifications/**")
                        .uri(notificationUri))
                .route("reservation-service-api", route -> route
                        .path(
                                "/api/v1/reservations/**",
                                "/api/v1/study-teams/**",
                                "/api/v1/study-rooms/**",
                                "/api/v1/resources/**",
                                "/api/v1/slots/**",
                                "/api/v1/admin/**"
                        )
                        .uri(reservationUri))
                .build();
    }
}
