
package com.socialhub.publishingjob.bulk.service;

import com.socialhub.workspacemember.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkspaceBulkPermissionService {

    private final WorkspaceMemberRepository members;

    public void requireManager(UUID workspaceId, UUID userId) {

        boolean allowed =
                members.existsByIdWorkspaceIdAndIdUserIdAndRoleIn(
                        workspaceId,
                        userId,
                        List.of("OWNER", "ADMIN")
                );

        if (!allowed) {
            throw new AccessDeniedException(
                    "You do not have permission to manage bulk publishing"
            );
        }
    }
}
