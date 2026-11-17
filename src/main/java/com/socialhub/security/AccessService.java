package com.socialhub.security;

import java.util.*;

import lombok.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.*;
import org.springframework.security.core.context.*;
import com.socialhub.common.ApiException;
import com.socialhub.workspace.entity.*;
import com.socialhub.workspace.repository.*;
import com.socialhub.workspacemember.entity.*;
import com.socialhub.workspacemember.repository.*;

@Service
@RequiredArgsConstructor
public class AccessService {
    private final WorkspaceMemberRepository members;
    private final WorkspaceRepository workspaces;

    public UUID userId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) throw new ApiException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        return UUID.fromString(a.getName());
    }

    public Workspace workspace(UUID id) {
        Workspace w = workspaces.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Workspace not found"));
        if (!members.existsById(new WorkspaceMemberId(id, userId())))
            throw new ApiException(HttpStatus.FORBIDDEN, "Not a workspace member");
        return w;
    }

    public void admin(UUID id) {
        workspace(id);
        WorkspaceMember m = members.findById(new WorkspaceMemberId(id, userId())).orElseThrow();
        if (!List.of("OWNER", "ADMIN").contains(m.role))
            throw new ApiException(HttpStatus.FORBIDDEN, "Admin access required");
    }

    public void owner(UUID id) {
        workspace(id);
        WorkspaceMember m = members.findById(new WorkspaceMemberId(id, userId())).orElseThrow();
        if (!"OWNER".equals(m.role)) throw new ApiException(HttpStatus.FORBIDDEN, "Owner access required");
    }
}
