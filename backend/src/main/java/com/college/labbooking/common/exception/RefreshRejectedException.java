package com.college.labbooking.common.exception;

import org.springframework.http.HttpStatus;

public class RefreshRejectedException extends AppException {
    public RefreshRejectedException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }
}
