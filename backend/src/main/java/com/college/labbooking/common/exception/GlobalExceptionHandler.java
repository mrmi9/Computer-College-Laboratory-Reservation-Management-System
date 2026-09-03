package com.college.labbooking.common.exception;

import com.college.labbooking.common.api.ApiEnvelope;
import jakarta.persistence.OptimisticLockException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    ResponseEntity<ApiEnvelope<Void>> handleAppException(AppException exception) {
        return ResponseEntity.status(exception.status())
                .body(ApiEnvelope.error(exception.code(), exception.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiEnvelope<List<FieldViolation>>> handleValidation(MethodArgumentNotValidException exception) {
        List<FieldViolation> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(ApiEnvelope.error("VALIDATION_FAILED", "请求参数校验失败", details));
    }

    @ExceptionHandler({OptimisticLockException.class, OptimisticLockingFailureException.class})
    ResponseEntity<ApiEnvelope<Void>> handleOptimisticLock(Exception exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiEnvelope.error("RESOURCE_VERSION_CONFLICT", "资源已被其他操作更新，请刷新后重试", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiEnvelope<Void>> handleUnknown(Exception exception) {
        log.error("Unhandled request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiEnvelope.error("INTERNAL_ERROR", "服务器暂时无法处理请求", null));
    }
}
