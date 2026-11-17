package com.socialhub.auth.controller;

import com.socialhub.auth.dto.AuthDto;
import com.socialhub.auth.service.interfaces.AuthService;
import com.socialhub.common.ApiException;
import com.socialhub.response.ApiResponse;
import com.socialhub.security.AuthCookieService;
import com.socialhub.user.entity.User;
import com.socialhub.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService service;
    private final UserRepository users;
    private final AuthCookieService cookies;

    @GetMapping("/csrf")
    public ResponseEntity<ApiResponse<String>> csrf(CsrfToken csrfToken) {
        return ResponseEntity.ok(ApiResponse.success("CSRF token ready", csrfToken.getToken()));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthDto.Profile>> register(
            @Valid @RequestBody AuthDto.Register request, HttpServletResponse response) {
        AuthDto.Token result = service.register(request);
        cookies.write(response, result.token());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(201,
                "Register Successfully", profile(result.userId())));
    }


    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthDto.Profile>> login(
            @Valid @RequestBody AuthDto.Login request,
            @RequestHeader(
                    value = "X-XSRF-TOKEN",
                    required = false
            ) String csrfToken,
            HttpServletResponse response
    ) {
        AuthDto.Token result = service.login(request);

        cookies.write(response, result.token());

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Login Successfully",
                        profile(result.userId())
                )
        );
    }


    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthDto.Profile>> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return ResponseEntity.ok(ApiResponse.success("Get Current User Successfully",
                profile(UUID.fromString(authentication.getName()))));
    }

    /**
     * Clears the browser cookie. Previously issued JWTs remain valid until expiration.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        // Xóa JWT authentication cookie
        cookies.clear(response);

        // Xóa CSRF token và cookie XSRF-TOKEN
        CookieCsrfTokenRepository csrfRepository =
                CookieCsrfTokenRepository.withHttpOnlyFalse();

        csrfRepository.saveToken(null, request, response);

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Logout Successfully",
                        null
                )
        );
    }

    private AuthDto.Profile profile(UUID id) {
        User user = users.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Not authenticated"));
        return new AuthDto.Profile(user.id, user.email, user.displayName, user.role);
    }
}
