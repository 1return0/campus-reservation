package com.campusreserve.common.api;

/** Stable success envelope shared by the public service APIs. */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "ok", data);
    }
}
