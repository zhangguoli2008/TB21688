package com.smartprocure.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private final ApiErrors errors;
    public GlobalExceptionHandler(ApiErrors errors) { this.errors = errors; }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrors.ErrorBody> domain(ApiException ex, HttpServletRequest request) { return response(request, ex.status(), ex.code(), ex.getMessage()); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiErrors.ErrorBody> invalid(Exception ex, HttpServletRequest request) { return response(request, 400, "VALIDATION_ERROR", "请求参数无效"); }
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ApiErrors.ErrorBody> missing(Exception ex, HttpServletRequest request) { return response(request, 404, "NOT_FOUND", "资源不存在"); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrors.ErrorBody> method(Exception ex, HttpServletRequest request) { return response(request, 405, "METHOD_NOT_ALLOWED", "请求方法不支持"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrors.ErrorBody> unexpected(Exception ex, HttpServletRequest request) { return response(request, 500, "INTERNAL_ERROR", "服务内部错误"); }
    private ResponseEntity<ApiErrors.ErrorBody> response(HttpServletRequest request, int status, String code, String message) {
        return ResponseEntity.status(status).body(errors.body(request, code, message));
    }
}
