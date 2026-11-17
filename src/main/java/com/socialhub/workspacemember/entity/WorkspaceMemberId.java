package com.socialhub.workspacemember.entity;

import jakarta.persistence.*; import lombok.*; import java.util.*; import java.io.*;
@Embeddable @Getter @Setter @NoArgsConstructor @EqualsAndHashCode public class WorkspaceMemberId implements Serializable { @Column(name="workspace_id") private UUID workspaceId; @Column(name="user_id") private UUID userId; public WorkspaceMemberId(UUID workspaceId, UUID userId) { this.workspaceId = workspaceId; this.userId = userId; } }
