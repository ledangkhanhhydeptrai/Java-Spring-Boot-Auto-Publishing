package com.socialhub.socialaccount.dto;

import java.util.*;

import jakarta.validation.constraints.*;

public final class SocialAccountDto {
    public record Create(@NotBlank String platform, @NotBlank String externalAccountId, @NotBlank String displayName) {
    }

    public record View(UUID id, String platform, String externalAccountId, String displayName, String status) {
    }
}
