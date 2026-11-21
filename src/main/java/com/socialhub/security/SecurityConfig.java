package com.socialhub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialhub.response.ApiResponse;
import com.socialhub.user.entity.User;
import com.socialhub.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtService jwt, UserRepository users,
                              AuthCookieService cookies, ObjectMapper mapper,
                              @Value("${app.cors-origin}") String origin,
                              @Value("${app.cookie-secure:false}") boolean cookieSecure,
                              @Value("${app.cookie-same-site:Lax}") String cookieSameSite) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(builder -> builder
                .path("/").secure(cookieSecure).sameSite(cookieSameSite));
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http.csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler)
                        // Machine-to-machine worker endpoint uses its own authentication mechanism.
                        .ignoringRequestMatchers("/internal/group-worker/**"))
                .cors(cors -> cors.configurationSource(req -> {
                    CorsConfiguration c = new CorsConfiguration();
                    c.setAllowedOrigins(List.of(origin));
                    c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                    c.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "X-CSRF-TOKEN"));
                    c.setAllowCredentials(true);
                    return c;
                }))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, mapper, 401, "Not authenticated"))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, mapper, 403, "Access denied")))

                .authorizeHttpRequests(a -> a
                        .requestMatchers(
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/csrf",
                                "/actuator/health",

                                // Threads OAuth
                                "/api/threads/oauth/callback",
                                "/api/threads/oauth/bridge",

                                // Facebook OAuth
                                "/api/facebook/oauth/callback",
                                "/api/facebook/oauth/bridge",

                                // Group Worker
                                "/internal/group-worker/**",
                                "/*.png",
                                "/*.jpg",
                                "/*.jpeg",
                                "/*.webp",
                                "/*.ico",
                                "/favicon.ico",
                                // Swagger
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .anyRequest().authenticated())

                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain) throws ServletException, IOException {
                        String token = cookies.read(request);
                        if (token != null && !token.isBlank()) {
                            try {
                                UUID id = jwt.verify(token);
                                User u = users.findById(id).orElse(null);
                                if (u != null) {
                                    SecurityContextHolder.getContext().setAuthentication(
                                            new UsernamePasswordAuthenticationToken(u.id.toString(), null,
                                                    List.of(new SimpleGrantedAuthority("ROLE_" + u.role))));
                                }
                            } catch (Exception ignored) {
                                SecurityContextHolder.clearContext();
                            }
                        }
                        chain.doFilter(request, response);
                    }
                }, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeError(HttpServletResponse response, ObjectMapper mapper,
                                   int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), ApiResponse.error(status, message));
    }
}
