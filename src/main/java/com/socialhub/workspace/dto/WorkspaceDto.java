package com.socialhub.workspace.dto;

import java.util.*;
import java.time.*;

import jakarta.validation.constraints.*;

public final class WorkspaceDto {
    public record Create(@NotBlank String name) {
    }

    public record View(UUID id, String name, OffsetDateTime createdAt) {
    }
}
