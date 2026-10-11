package com.campusreserve.security;

import java.util.Set;

public record CampusAuthenticatedPrincipal(String userId, Set<String> roles, String studentNumber) {

    public CampusAuthenticatedPrincipal(String userId, Set<String> roles) {
        this(userId, roles, null);
    }

    public static CampusAuthenticatedPrincipal from(JwtPrincipal principal) {
        return new CampusAuthenticatedPrincipal(principal.subject(), principal.roles(), principal.studentNumber());
    }
}
