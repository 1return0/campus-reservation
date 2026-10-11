package com.campusreserve.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "campus-reserve.security.jwt.hmac-base64-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "campus-reserve.gateway.identity-uri=http://127.0.0.1:8201",
        "campus-reserve.gateway.reservation-uri=http://127.0.0.1:8202",
        "campus-reserve.gateway.notification-uri=http://127.0.0.1:8203",
        "spring.cloud.nacos.discovery.enabled=false"
})
class GatewayLandingPageTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void serves_the_browser_entry_without_requiring_a_token() {
        webTestClient.get().uri("http://127.0.0.1:" + port + "/")
                .exchange()
                .expectStatus().isOk();
        webTestClient.get().uri("http://127.0.0.1:" + port + "/index.html")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                        .contains("CampusReserve", "学生注册", "讲座等活动 · 固定场次报名",
                                "研讨室 · 自选使用区间预约", "讲座标题（讲座必填）",
                                "主讲人（讲座必填）", "活动介绍（讲座必填）"));
        webTestClient.get().uri("http://127.0.0.1:" + port + "/app.js")
                .exchange()
                .expectStatus().isOk();
    }
}
