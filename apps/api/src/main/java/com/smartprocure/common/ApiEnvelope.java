package com.smartprocure.common;

import jakarta.servlet.http.HttpServletRequest;

public record ApiEnvelope<T>(T data, String requestId) {
    public static <T> ApiEnvelope<T> of(T data, HttpServletRequest request) {
        return new ApiEnvelope<>(data, RequestIdFilter.requestId(request));
    }
}
