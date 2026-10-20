package com.smartprocure.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class ApiErrors {
    public record ErrorBody(String code, String message, String requestId) { }
    private final ObjectMapper mapper;
    public ApiErrors(ObjectMapper mapper) { this.mapper = mapper; }
    public ErrorBody body(HttpServletRequest request, String code, String message) {
        return new ErrorBody(code, message, RequestIdFilter.requestId(request));
    }
    public void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), body(request, code, message));
    }
}
