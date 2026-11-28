package com.socialhub.facebookoauth.controller;

import com.socialhub.facebookoauth.service.implement.FacebookIdentitySyncQueue;
import com.socialhub.security.AccessService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/facebook-identities/sync-runs")
public class FacebookIdentitySyncQueueController {
    private static final Logger LOG = LoggerFactory.getLogger(FacebookIdentitySyncQueueController.class);
    private final AccessService access;
    private final FacebookIdentitySyncQueue queue;

    public FacebookIdentitySyncQueueController(AccessService access, FacebookIdentitySyncQueue queue) {
        this.access = access;
        this.queue = queue;
    }

    public record StartRequest(String facebookUserId) {
    }

    @PostMapping
    public FacebookIdentitySyncQueue.Run start(@PathVariable UUID workspaceId, @RequestBody StartRequest body) {
        access.admin(workspaceId);
        try {
            return queue.start(workspaceId, body.facebookUserId());
        } catch (RuntimeException ex) {
            logFailure("start", ex);
            throw ex;
        }
    }

    @GetMapping("/latest")
    public FacebookIdentitySyncQueue.Latest latest(@PathVariable UUID workspaceId) {
        access.admin(workspaceId);
        try {
            return queue.latest(workspaceId);
        } catch (RuntimeException ex) {
            logFailure("latest", ex);
            throw ex;
        }
    }

    // Queue metadata only: never log tokens, request bodies, SQL parameters or provider payloads.
    private static void logFailure(String operation, RuntimeException ex) {
        Throwable cause = ex;
        String sqlState = "n/a";
        String rootType = ex.getClass().getSimpleName();
        for (int depth = 0; cause != null && depth < 20; depth++) {
            rootType = cause.getClass().getSimpleName();
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                sqlState = sql.getSQLState();
            }
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        LOG.warn("[FB-SYNC-QUEUE] operation={} exception={} root={} SQLState={}",
                operation, ex.getClass().getSimpleName(), rootType, sqlState);
    }
}
