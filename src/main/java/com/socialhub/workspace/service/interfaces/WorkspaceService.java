package com.socialhub.workspace.service.interfaces;

import com.socialhub.workspace.dto.WorkspaceDto;
import java.util.List;
import java.util.UUID;

public interface WorkspaceService {
    WorkspaceDto.View create(WorkspaceDto.Create request);
    List<WorkspaceDto.View> list();
    WorkspaceDto.View detail(UUID id);
    WorkspaceDto.View rename(UUID id, WorkspaceDto.Create request);
    void delete(UUID id);
}
