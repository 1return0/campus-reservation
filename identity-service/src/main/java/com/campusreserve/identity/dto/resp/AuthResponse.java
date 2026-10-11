package com.campusreserve.identity.dto.resp;

public record AuthResponse(String accessToken, long expiresInSeconds) {
}
