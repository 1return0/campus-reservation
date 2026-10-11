package com.campusreserve.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampusJwtServiceTest {

    private static final String TEST_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void issued_token_recovers_subject_and_roles() {
        CampusJwtService service = new CampusJwtService(TEST_SECRET);

        String token = service.issue("student-1001", List.of("STUDENT"), Duration.ofMinutes(5));

        assertThat(service.verify(token))
                .isEqualTo(new JwtPrincipal("student-1001", java.util.Set.of("STUDENT")));
    }

    @Test
    void student_number_is_a_signed_identity_claim() {
        CampusJwtService service = new CampusJwtService(TEST_SECRET);
        String token = service.issue("student-1001", List.of("STUDENT"),
                "20260001", Duration.ofMinutes(5));

        assertThat(service.verify(token).studentNumber()).isEqualTo("20260001");
        assertThat(CampusAuthenticatedPrincipal.from(service.verify(token)).studentNumber())
                .isEqualTo("20260001");
    }

    @Test
    void tampered_token_is_rejected() {
        CampusJwtService service = new CampusJwtService(TEST_SECRET);
        String token = service.issue("student-1001", List.of("STUDENT"), Duration.ofMinutes(5));

        assertThatThrownBy(() -> service.verify(token + "x"))
                .isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void token_from_a_different_issuer_is_rejected_even_when_the_key_matches() {
        CampusJwtService campusReserve = new CampusJwtService(TEST_SECRET, "campus-reserve");
        CampusJwtService anotherSystem = new CampusJwtService(TEST_SECRET, "another-system");
        String token = anotherSystem.issue("student-1001", List.of("STUDENT"), Duration.ofMinutes(5));

        assertThatThrownBy(() -> campusReserve.verify(token))
                .isInstanceOf(JwtVerificationException.class);
    }
}
