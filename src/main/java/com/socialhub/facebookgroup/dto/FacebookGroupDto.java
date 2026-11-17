package com.socialhub.facebookgroup.dto;

import java.util.*; import java.time.*; import jakarta.validation.constraints.*;
public final class FacebookGroupDto { public record Create(@NotBlank String externalGroupId,@NotBlank String name,@NotBlank String groupUrl){} public record View(UUID id,String externalGroupId,String name,String groupUrl){} }
