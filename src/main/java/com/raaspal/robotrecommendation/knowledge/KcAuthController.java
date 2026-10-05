package com.raaspal.robotrecommendation.knowledge;

import com.raaspal.robotrecommendation.auth.dto.AuthResponse;
import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/kc/auth")
@RequiredArgsConstructor
public class KcAuthController {
    private final KcAuthService accounts;

    // Avoid record-generated toString methods containing passwords, codes or tickets.
    public record SendCode(String purpose, String email) {}
    public record VerifyCode(String purpose, String email, String code) {
        @Override public String toString() { return "VerifyCode[redacted]"; }
    }
    public record SignUp(String ticket, String name, String password, String confirm) {
        @Override public String toString() { return "SignUp[redacted]"; }
    }
    public record Reset(String ticket, String password, String confirm) {
        @Override public String toString() { return "Reset[redacted]"; }
    }

    @PostMapping("/send-code")
    public ApiResponse<Map<String, String>> send(@RequestBody SendCode body) {
        return ApiResponse.success(Map.of("email", accounts.sendCode(body.purpose(), body.email())));
    }

    @PostMapping("/verify-code")
    public ApiResponse<Map<String, String>> verify(@RequestBody VerifyCode body) {
        return ApiResponse.success(Map.of("ticket", accounts.verifyCode(body.purpose(), body.email(), body.code())));
    }

    @PostMapping("/signup")
    public ApiResponse<AuthResponse> signup(@RequestBody SignUp body) {
        return ApiResponse.success(accounts.signUp(body.ticket(), body.name(), body.password(), body.confirm()));
    }

    @PostMapping("/reset")
    public ApiResponse<AuthResponse> reset(@RequestBody Reset body) {
        return ApiResponse.success(accounts.reset(body.ticket(), body.password(), body.confirm()));
    }

    @GetMapping("/me")
    public ApiResponse<KcAuthService.Me> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(accounts.me(principal.getUsername()));
    }
}
