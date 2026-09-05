package com.college.labbooking.common.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;

public record ApiEnvelope<T>(String code, String message, T data, String requestId, OffsetDateTime timestamp) {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    public static <T> ApiEnvelope<T> ok(T data) {
        return new ApiEnvelope<>("OK", "success", data, RequestIds.current(), OffsetDateTime.now(BUSINESS_ZONE));
    }

    public static <T> ApiEnvelope<T> error(String code, String message, T details) {
        return new ApiEnvelope<>(code, message, details, RequestIds.current(), OffsetDateTime.now(BUSINESS_ZONE));
    }
}
