package com.raaspal.robotrecommendation.knowledge;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@Order(-2)
public class KcAuthErrors {
    @ExceptionHandler(KcAuthException.class)
    public ResponseEntity<ApiResponse<Void>> expected(KcAuthException ex) {
        var response = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfter() > 0) response.header("Retry-After", Long.toString(ex.getRetryAfter()));
        return response.body(ApiResponse.error(ex.getMessage()));
    }
}
