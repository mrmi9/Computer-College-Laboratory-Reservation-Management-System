package com.college.labbooking.common.exception;

import com.college.labbooking.common.api.ApiEnvelope;
import jakarta.persistence.OptimisticLockException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiEnvelope<Void>> handleDataConflict(DataIntegrityViolationException exception) {
        log.info("Database constraint rejected request: {}", exception.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiEnvelope.error("DATA_CONFLICT", "数据与现有记录冲突或不满足约束", null));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiEnvelope<Void>> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiEnvelope.error("ACCESS_DENIED", "没有执行该操作的权限", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiEnvelope<Void>> handleUnknown(Exception exception) {
        log.error("Unhandled request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiEnvelope.error("INTERNAL_ERROR", "服务器暂时无法处理请求", null));
    }
}
