package com.raaspal.robotrecommendation.knowledge;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/** Do not pass SMTP exceptions, request bodies or credentials into the global logger. */
@RestControllerAdvice(assignableTypes = KcAuthController.class)
@Order(-1)
public class KcControllerErrors {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformed(HttpServletRequest request) {
        String path = request.getRequestURI();
        String code = path.endsWith("/verify-code") ? "wrong"
                : path.endsWith("/signup") || path.endsWith("/reset") ? "expired" : "invalid";
        return ResponseEntity.badRequest().body(ApiResponse.error(code));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> concurrentSignup() {
        return ResponseEntity.badRequest().body(ApiResponse.error("expired"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unavailable() {
        return ResponseEntity.status(503).body(ApiResponse.error("unavailable"));
    }
}
