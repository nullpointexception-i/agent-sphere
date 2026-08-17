package com.buukle.agent.common.exception;

import com.buukle.agent.common.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Request body not readable: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(CommonErrorCode.PARAM_INVALID.getCode(),
                        CommonErrorCode.PARAM_INVALID.getMessage(), "请求体格式错误，请检查字段类型"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(CommonErrorCode.RESOURCE_NOT_FOUND.getCode(),
                        CommonErrorCode.RESOURCE_NOT_FOUND.getMessage(), e.getResourcePath()));
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
