package com.smartprocure.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiErrorController implements ErrorController {
    private final ApiErrors errors;
    public ApiErrorController(ApiErrors errors) { this.errors = errors; }
    @RequestMapping("/error")
    ResponseEntity<ApiErrors.ErrorBody> error(HttpServletRequest request) {
        Object raw = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = raw instanceof Integer value ? value : 500;
        String code = switch (status) { case 400 -> "VALIDATION_ERROR"; case 401 -> "UNAUTHENTICATED"; case 403 -> "FORBIDDEN"; case 404 -> "NOT_FOUND"; case 405 -> "METHOD_NOT_ALLOWED"; default -> "INTERNAL_ERROR"; };
        return ResponseEntity.status(status).body(errors.body(request, code, "请求无法完成"));
    }
}
