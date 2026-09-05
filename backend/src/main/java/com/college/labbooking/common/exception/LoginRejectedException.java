package com.college.labbooking.common.exception;

import org.springframework.http.HttpStatus;

public class LoginRejectedException extends AppException {
    public LoginRejectedException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
