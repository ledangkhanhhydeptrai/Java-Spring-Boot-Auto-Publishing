
package com.socialhub.facebookoauth.service.interfaces;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

public interface FacebookOAuthService {

    ResponseEntity<Void> start(
            UUID workspaceId,
            UUID userId
    );

    ResponseEntity<Void> bridge(
            String state,
            HttpServletResponse response
    );

    ResponseEntity<Void> callback(
            String code,
            String state,
            String error,
            HttpServletRequest request,
            HttpServletResponse response
    );
}
