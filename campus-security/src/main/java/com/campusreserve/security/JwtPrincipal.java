package com.campusreserve.security;

import java.util.Set;

public record JwtPrincipal(String subject, Set<String> roles, String studentNumber) {

    public JwtPrincipal(String subject, Set<String> roles) {
        this(subject, roles, null);
    }
}
