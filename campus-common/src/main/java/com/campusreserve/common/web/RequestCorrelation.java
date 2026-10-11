package com.campusreserve.common.web;

import java.util.UUID;
import java.util.regex.Pattern;

public final class RequestCorrelation {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private RequestCorrelation() {
    }

    public static String normalize(String candidate) {
        if (candidate != null && SAFE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
