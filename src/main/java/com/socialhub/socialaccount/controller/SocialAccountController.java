package com.socialhub.socialaccount.controller;

import java.util.*;

import lombok.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.socialhub.socialaccount.dto.SocialAccountDto;
import com.socialhub.socialaccount.service.interfaces.SocialAccountService;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/social-accounts")
@RequiredArgsConstructor
public class SocialAccountController {
    private final SocialAccountService service;

    @PostMapping
    public SocialAccountDto.View create(@PathVariable UUID workspaceId, @Valid @RequestBody SocialAccountDto.Create r) {
        return service.create(workspaceId, r);
    }

    @GetMapping
    public List<SocialAccountDto.View> list(@PathVariable UUID workspaceId) {
        return service.list(workspaceId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID workspaceId, @PathVariable UUID id) {
        service.delete(workspaceId, id);
    }
}
