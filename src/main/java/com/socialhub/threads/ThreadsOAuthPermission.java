package com.socialhub.threads;

import com.socialhub.workspacemember.entity.WorkspaceMemberId;
import com.socialhub.workspacemember.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

@Component @RequiredArgsConstructor
public class ThreadsOAuthPermission {
    private final WorkspaceMemberRepository members;
    public boolean canAdmin(UUID workspaceId, UUID userId) {
        return members.findById(new WorkspaceMemberId(workspaceId, userId))
                .map(member -> List.of("OWNER", "ADMIN").contains(member.role)).orElse(false);
    }
}
