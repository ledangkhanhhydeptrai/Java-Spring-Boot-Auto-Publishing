package com.socialhub.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AuthDto {
    private AuthDto() {}
    public record Register(@NotBlank @Email String email, @NotBlank String displayName,
                           @NotBlank @Size(min = 8) String password) {}
    public record Login(@NotBlank String email, @NotBlank String password) {}
    public record Token(String token, UUID userId, String email, String displayName) {}
    public record Profile(UUID userId, String email, String displayName, String role) {}
}
