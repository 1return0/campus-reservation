package com.campusreserve.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/** Minimal token contract shared by the issuer and resource boundary only. */
public class CampusJwtService {

    public static final String DEFAULT_ISSUER = "campus-reserve";

    private final SecretKey signingKey;
    private final String issuer;

    public CampusJwtService(String base64Secret) {
        this(base64Secret, DEFAULT_ISSUER);
    }

    public CampusJwtService(String base64Secret, String issuer) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer must not be blank");
        }
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
        this.issuer = issuer;
    }

    public String issue(String subject, Collection<String> roles, Duration ttl) {
        return issue(subject, roles, null, ttl);
    }

    public String issue(String subject, Collection<String> roles, String studentNumber, Duration ttl) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(issuer)
                .subject(subject)
                .claim("roles", roles.stream().sorted().toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        if (studentNumber != null && !studentNumber.isBlank()) {
            builder.claim("studentNo", studentNumber);
        }
        return builder.signWith(signingKey).compact();
    }

    public JwtPrincipal verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new IllegalArgumentException("token subject is blank");
            }
            Object rawRoles = claims.get("roles");
            if (!(rawRoles instanceof Collection<?> values)) {
                throw new IllegalArgumentException("token roles claim is invalid");
            }
            Set<String> roles = new LinkedHashSet<>();
            for (Object value : values) {
                if (value == null || value.toString().isBlank()) {
                    throw new IllegalArgumentException("token role is blank");
                }
                roles.add(value.toString());
            }
            String studentNumber = claims.get("studentNo", String.class);
            return new JwtPrincipal(subject, Set.copyOf(roles), studentNumber);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new JwtVerificationException("token verification failed", exception);
        }
    }
}
