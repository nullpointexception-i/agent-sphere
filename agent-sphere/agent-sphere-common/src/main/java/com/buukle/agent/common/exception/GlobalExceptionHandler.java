package com.buukle.agent.common.exception;

import com.buukle.agent.common.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorResponse> handleBizException(BizException e) {
        log.warn("BizException: code={}, message={}", e.getErrorCode(), e.getMessage());
        HttpStatus status = CommonErrorCode.FORBIDDEN.getCode().equals(e.getErrorCode())
                ? HttpStatus.FORBIDDEN
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(new ErrorResponse(e.getErrorCode(), e.getMessage(), e.getUserTip()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(CommonErrorCode.PARAM_INVALID.getCode(), CommonErrorCode.PARAM_INVALID.getMessage(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(CommonErrorCode.PARAM_INVALID.getCode(), CommonErrorCode.PARAM_INVALID.getMessage(),
                        e.getBindingResult().getFieldErrors().stream()
                                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                                .reduce((a, b) -> a + "; " + b)
                                .orElse(CommonErrorCode.PARAM_INVALID.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnknown(Exception e, HttpServletResponse response) {
        if ("text/event-stream".equals(response.getContentType())) {
//            log.warn("warnSSE client disconnected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        log.error("unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(CommonErrorCode.INTERNAL_ERROR.getCode(), CommonErrorCode.INTERNAL_ERROR.getMessage(), CommonErrorCode.INTERNAL_ERROR.getUserTip()));
    }
}
