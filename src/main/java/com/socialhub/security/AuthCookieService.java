package com.socialhub.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AuthCookieService {
    public static final String NAME = "SOCIALHUB_AUTH";
    private final boolean secure;
    private final String sameSite;
    private final long maxAgeSeconds;

    public AuthCookieService(
            @Value("${app.cookie-secure:false}") boolean secure,
            @Value("${app.cookie-same-site:Lax}") String sameSite,
            @Value("${app.jwt-expiration-minutes:1440}") long expirationMinutes) {
        if (!sameSite.equals("Lax") && !sameSite.equals("Strict") && !sameSite.equals("None")) {
            throw new IllegalArgumentException("COOKIE_SAME_SITE must be Lax, Strict, or None");
        }
        if (sameSite.equals("None") && !secure) {
            throw new IllegalArgumentException("SameSite=None requires COOKIE_SECURE=true");
        }
        this.secure = secure;
        this.sameSite = sameSite;
        this.maxAgeSeconds = Math.multiplyExact(expirationMinutes, 60);
    }

    public void write(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, maxAgeSeconds).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
    }

    public String read(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private ResponseCookie cookie(String value, long seconds) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ofSeconds(seconds))
                .build();
    }
}
