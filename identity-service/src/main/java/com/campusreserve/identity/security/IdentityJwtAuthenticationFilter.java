package com.campusreserve.identity.security;

import com.campusreserve.security.CampusAuthenticatedPrincipal;
import com.campusreserve.security.CampusJwtService;
import com.campusreserve.security.JwtVerificationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class IdentityJwtAuthenticationFilter extends OncePerRequestFilter {

    private final CampusJwtService jwtService;

    public IdentityJwtAuthenticationFilter(CampusJwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/auth/") || path.startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            writeUnauthorized(response);
            return;
        }
        try {
            CampusAuthenticatedPrincipal principal = CampusAuthenticatedPrincipal.from(
                    jwtService.verify(authorization.substring("Bearer ".length()))
            );
            var authorities = principal.roles().stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, authorities)
            );
            filterChain.doFilter(request, response);
        } catch (JwtVerificationException exception) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response);
        }
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":401,\"message\":\"unauthorized\",\"data\":null}");
    }
}
