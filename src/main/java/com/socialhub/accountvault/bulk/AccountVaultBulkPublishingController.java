package com.socialhub.accountvault.bulk;

import com.socialhub.common.ApiException;
import com.socialhub.publishingjob.bulk.dto.FacebookBulkRunResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/account-vault/bulk-publishing")
public class AccountVaultBulkPublishingController {
    private final AccountVaultBulkPublishingService service;

    @PostMapping("/preview")
    public AccountVaultBulkPublishingService.Preview preview(
            @PathVariable UUID workspaceId,
            @RequestBody AccountVaultBulkPublishingService.BulkRequest request) {
        return service.preview(workspaceId, request);
    }

    @PostMapping("/schedule")
    public AccountVaultBulkPublishingService.Scheduled schedule(
            @PathVariable UUID workspaceId,
            Authentication authentication,
            @RequestBody AccountVaultBulkPublishingService.BulkRequest request) {
        return service.schedule(workspaceId, userId(authentication), request);
    }

    @PostMapping("/facebook-runs/{runId}/start")
    public FacebookBulkRunResponse startFacebook(
            @PathVariable UUID workspaceId,
            @PathVariable UUID runId,
            Authentication authentication) {
        return service.startFacebook(workspaceId, userId(authentication), runId);
    }

    @GetMapping("/facebook-runs/{runId}/progress")
    public AccountVaultBulkPublishingService.FacebookProgress progress(
            @PathVariable UUID workspaceId,
            @PathVariable UUID runId,
            Authentication authentication) {
        return service.progress(workspaceId, userId(authentication), runId);
    }

    private UUID userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid authenticated user");
        }
    }
}
