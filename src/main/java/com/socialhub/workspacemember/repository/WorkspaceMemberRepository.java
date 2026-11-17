
package com.socialhub.workspacemember.repository;

import com.socialhub.workspacemember.entity.WorkspaceMember;
import com.socialhub.workspacemember.entity.WorkspaceMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkspaceMemberRepository
        extends JpaRepository<WorkspaceMember, WorkspaceMemberId> {

    List<WorkspaceMember> findByIdUserId(UUID userId);

    List<WorkspaceMember> findByIdWorkspaceId(UUID workspaceId);

    boolean existsByIdWorkspaceIdAndIdUserIdAndRoleIn(
            UUID workspaceId,
            UUID userId,
            List<String> roles
    );
}
