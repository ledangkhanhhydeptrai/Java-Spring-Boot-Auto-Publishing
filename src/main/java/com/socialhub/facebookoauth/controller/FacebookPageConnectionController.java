package com.socialhub.facebookoauth.controller;

import com.socialhub.common.ApiException;
import com.socialhub.facebookoauth.entity.FacebookPageConnection;
import com.socialhub.facebookoauth.repository.FacebookPageConnectionRepository;
import com.socialhub.security.AccessService;
import com.socialhub.socialaccount.entity.SocialAccount;
import com.socialhub.socialaccount.repository.SocialAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import com.socialhub.publishingjob.entity.PublishingJob;
import com.socialhub.publishingjob.repository.PublishingJobRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Never exposes access tokens, plaintext or encrypted. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/facebook-page-connections")
public class FacebookPageConnectionController {
    private final FacebookPageConnectionRepository connections;
    private final SocialAccountRepository accounts;
    private final AccessService access;
    private final JdbcTemplate jdbc;
    private final PublishingJobRepository jobs;

    public record ConnectionView(UUID id, UUID socialAccountId, String facebookUserId,
                                 String status, OffsetDateTime lastConnectedAt,
                                 OffsetDateTime tokenExpiresAt, boolean tokenStored) {}

    private ConnectionView view(FacebookPageConnection c) {
        return new ConnectionView(c.getId(), c.getSocialAccount().getId(),
                c.getFacebookUserId(), c.getStatus(), c.getLastConnectedAt(),
                c.getTokenExpiresAt(), c.getAccessTokenEncrypted() != null
                && !c.getAccessTokenEncrypted().isBlank());
    }

    private SocialAccount page(UUID workspaceId, UUID socialAccountId) {
        SocialAccount account = accounts.findById(socialAccountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Page not found"));
        if (!workspaceId.equals(account.getWorkspace().getId())
                || !"FACEBOOK_PAGE".equals(account.getPlatform())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Page not found in workspace");
        }
        return account;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<ConnectionView> list(@PathVariable UUID workspaceId,
                                     @RequestParam UUID socialAccountId) {
        access.admin(workspaceId);
        page(workspaceId, socialAccountId);
        return connections.findBySocialAccountIdOrderByLastConnectedAtDescIdAsc(socialAccountId)
                .stream().map(this::view).toList();
    }


    public record BindRequest(UUID connectionId) {}
    public record JobBindingView(UUID jobId, UUID connectionId, String facebookUserId) {}

    /** Bind a specific authorized grantor before the first attempt of a pending Job. */
    @PostMapping("/jobs/{jobId}/bind")
    @Transactional
    public JobBindingView bind(@PathVariable UUID workspaceId, @PathVariable UUID jobId,
                               @RequestBody BindRequest request) {
        access.admin(workspaceId);
        if (request == null || request.connectionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "connectionId is required");
        }
        PublishingJob job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Publishing job not found"));
        SocialAccount account = job.getSocialAccount();
        page(workspaceId, account.getId());
        if (!"PENDING".equals(job.getStatus()) || job.getAttemptCount() != 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a PENDING job with zero attempts can be bound");
        }
        FacebookPageConnection connection = connections.findById(request.connectionId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Connection not found"));
        if (!account.getId().equals(connection.getSocialAccount().getId())
                || !"CONNECTED".equals(connection.getStatus())
                || connection.getAccessTokenEncrypted() == null
                || connection.getAccessTokenEncrypted().isBlank()
                || (connection.getTokenExpiresAt() != null
                && !connection.getTokenExpiresAt().isAfter(OffsetDateTime.now()))) {
            throw new ApiException(HttpStatus.CONFLICT, "Connection not eligible for this Page");
        }
        job.setFacebookConnection(connection);
        jobs.save(job);
        return new JobBindingView(jobId, connection.getId(), connection.getFacebookUserId());
    }

    @GetMapping("/jobs/{jobId}/binding")
    @Transactional(readOnly = true)
    public JobBindingView binding(@PathVariable UUID workspaceId, @PathVariable UUID jobId) {
        access.admin(workspaceId);
        PublishingJob job = jobs.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Publishing job not found"));
        page(workspaceId, job.getSocialAccount().getId());
        FacebookPageConnection connection = job.getFacebookConnection();
        return new JobBindingView(jobId, connection == null ? null : connection.getId(),
                connection == null ? null : connection.getFacebookUserId());
    }

    @PostMapping("/{connectionId}/disconnect")
    @Transactional
    public ConnectionView disconnect(@PathVariable UUID workspaceId,
                                     @PathVariable UUID connectionId) {
        access.admin(workspaceId);
        FacebookPageConnection connection = connections.findById(connectionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Connection not found"));
        page(workspaceId, connection.getSocialAccount().getId());
        Integer jobsInFlight = jdbc.queryForObject("""
                SELECT COUNT(*) FROM publishing_jobs
                WHERE facebook_connection_id = ?
                  AND status IN ('PENDING', 'PROCESSING', 'RETRY')
                """, Integer.class, connectionId);
        if (jobsInFlight != null && jobsInFlight > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Connection is assigned to active publishing jobs; cancel or finish them first");
        }
        connection.setStatus("DISCONNECTED");
        // Revocation in SocialHub only; external Meta permissions are managed separately.
        return view(connections.save(connection));
    }
}
