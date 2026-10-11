package com.campusreserve.security;

public class JwtVerificationException extends RuntimeException {

    public JwtVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
