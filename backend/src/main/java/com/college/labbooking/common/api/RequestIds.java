package com.college.labbooking.common.api;

import java.util.UUID;
import org.slf4j.MDC;

public final class RequestIds {
    public static final String MDC_KEY = "requestId";

    private RequestIds() {}

    public static String current() {
        String requestId = MDC.get(MDC_KEY);
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }
}
