
package com.socialhub.postapproval.controller;

import com.socialhub.postapproval.dto.PostApprovalDto;
import com.socialhub.postapproval.service.interfaces.PostApprovalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/posts/{postId}/approval")
@RequiredArgsConstructor
public class PostApprovalController {

    private final PostApprovalService service;

    @PostMapping("/submit")
    public ResponseEntity<PostApprovalDto.Status> submit(
            @PathVariable UUID postId
    ) {
        return ResponseEntity.ok(service.submit(postId));
    }

    @PostMapping("/approve")
    public ResponseEntity<PostApprovalDto.Status> approve(
            @PathVariable UUID postId
    ) {
        return ResponseEntity.ok(service.approve(postId));
    }

    @PostMapping("/reject")
    public ResponseEntity<PostApprovalDto.Status> reject(
            @PathVariable UUID postId,
            @Valid @RequestBody PostApprovalDto.Reject request
    ) {
        return ResponseEntity.ok(service.reject(postId, request));
    }

    @GetMapping("/status")
    public ResponseEntity<PostApprovalDto.Status> getStatus(
            @PathVariable UUID postId
    ) {
        return ResponseEntity.ok(service.getStatus(postId));
    }

    @GetMapping("/history")
    public ResponseEntity<List<PostApprovalDto.View>> history(
            @PathVariable UUID postId
    ) {
        return ResponseEntity.ok(service.history(postId));
    }
}
