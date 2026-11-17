package com.socialhub.workspacemember.controller;

import java.util.*;

import lombok.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import com.socialhub.user.entity.User;
import com.socialhub.user.repository.UserRepository;
import com.socialhub.workspacemember.entity.*;
import com.socialhub.workspacemember.repository.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/members")
@RequiredArgsConstructor
public class WorkspaceMemberController {
    private final WorkspaceMemberRepository members;
    private final UserRepository users;
    private final AccessService access;

    public record Member(UUID userId, String email, String role) {
    }

    public record Add(@NotBlank @Email String email, @NotBlank String role) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Member> list(@PathVariable UUID workspaceId) {
        access.workspace(workspaceId);

        return members.findByIdWorkspaceId(workspaceId)
                .stream()
                .map(m -> new Member(
                        m.getUser().getId(),
                        m.getUser().getEmail(),
                        m.getRole()
                ))
                .toList();
    }

    @PostMapping
    @Transactional
    public Member add(@PathVariable UUID workspaceId, @Valid @RequestBody Add r) {
        access.admin(workspaceId);
        if (!List.of("ADMIN", "MEMBER").contains(r.role()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid role");
        User u = users.findByEmailIgnoreCase(r.email()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (members.existsById(new WorkspaceMemberId(workspaceId, u.id))) {
            throw new ApiException(HttpStatus.CONFLICT, "User is already a workspace member");
        }
        WorkspaceMember m = new WorkspaceMember();
        m.id = new WorkspaceMemberId(workspaceId, u.id);
        m.workspace = access.workspace(workspaceId);
        m.user = u;
        m.role = r.role();
        members.save(m);
        return new Member(u.id, u.email, m.role);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID workspaceId, @PathVariable UUID userId) {
        access.owner(workspaceId);
        WorkspaceMemberId id = new WorkspaceMemberId(workspaceId, userId);
        WorkspaceMember m = members.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Member not found"));
        if ("OWNER".equals(m.role)) throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot remove owner");
        members.delete(m);
    }
}
