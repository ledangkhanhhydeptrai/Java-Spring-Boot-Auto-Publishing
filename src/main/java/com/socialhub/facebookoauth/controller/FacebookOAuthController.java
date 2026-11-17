
package com.socialhub.facebookoauth.controller;

import com.socialhub.facebookoauth.service.interfaces.FacebookOAuthService;
import com.socialhub.security.AccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/facebook/oauth")
@RequiredArgsConstructor
public class FacebookOAuthController {

    private final FacebookOAuthService facebookOAuthService;
    private final AccessService accessService;

    @GetMapping("/start")
    public ResponseEntity<Void> start(
            @RequestParam UUID workspaceId
    ) {
        accessService.admin(workspaceId);

        UUID userId = accessService.userId();

        return facebookOAuthService.start(
                workspaceId,
                userId
        );
    }

    @GetMapping("/bridge")
    public ResponseEntity<Void> bridge(
            @RequestParam String state,
            HttpServletResponse response
    ) {
        return facebookOAuthService.bridge(
                state,
                response
        );
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return facebookOAuthService.callback(
                code,
                state,
                error,
                request,
                response
        );
    }
}
