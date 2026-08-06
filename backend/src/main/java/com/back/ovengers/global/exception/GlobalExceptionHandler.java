package com.back.ovengers.global.exception;

import com.back.ovengers.global.response.ApiResponse;
import jakarta.persistence.LockTimeoutException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ===== 1. 비즈니스 예외 (직접 정의) =====

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<?>> handleCustomException(CustomException e) {
        log.warn("비즈니스 예외 발생: {}", e.getErrorCode());
        return buildErrorResponse(e.getErrorCode());
    }

    // ===== 2. 검증 관련 예외 (프레임워크) =====

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidationException(
            MethodArgumentNotValidException e
    ) {
        FieldError fieldError =
                e.getBindingResult().getFieldErrors().get(0);

        String field = fieldError.getField();
        String message = fieldError.getDefaultMessage();

        return buildErrorResponse(
                ErrorCode.MISSING_REQUIRED_FIELD,
                field + ": " + message
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleConstraintViolationException(
            ConstraintViolationException e
    ) {
        String message = e.getConstraintViolations()
                .iterator().next().getMessage();

        return buildErrorResponse(ErrorCode.INVALID_PATH_VARIABLE, message);
    }

    // ===== 3. 인증/인가 예외 (Spring Security) =====

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<?>> handleAccessDeniedException(
            AccessDeniedException e
    ) {
        log.warn("접근 거부 발생: {}", e.getMessage());
        return buildErrorResponse(ErrorCode.FORBIDDEN);
    }

    // ===== 4. 동시성/락 예외 (Spring Data JPA) =====

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<?>> handleOptimisticLockException(
            ObjectOptimisticLockingFailureException e
    ) {
        return buildErrorResponse(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);
    }

    @ExceptionHandler(LockTimeoutException.class)
    public ResponseEntity<ApiResponse<?>> handleLockTimeoutException(
            LockTimeoutException e
    ) {
        return buildErrorResponse(ErrorCode.LOCK_TIMEOUT);
    }

    // ===== 5. 파일 업로드 예외 =====

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<?>> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException e
    ) {
        return buildErrorResponse(ErrorCode.IMAGE_SIZE_EXCEEDED);
    }

    // ===== 6. 최종 캐치올 (예상하지 못한 모든 예외) =====

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
        log.error("예상하지 못한 예외 발생", e);
        return buildErrorResponse(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    // ===== 공통 헬퍼 =====

    // ErrorCode에 정의된 상태코드/메시지를 그대로 사용하는 경우
    private ResponseEntity<ApiResponse<?>> buildErrorResponse(ErrorCode errorCode) {
        return buildErrorResponse(errorCode, errorCode.getMessage());
    }

    // 필드 검증 에러처럼, 메시지를 동적으로 조합해야 하는 경우
    private ResponseEntity<ApiResponse<?>> buildErrorResponse(
            ErrorCode errorCode,
            String message
    ) {
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(new ApiResponse<>(errorCode.name(), message));
    }
}