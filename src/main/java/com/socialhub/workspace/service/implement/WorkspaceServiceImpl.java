package com.socialhub.workspace.service.implement;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialhub.workspace.dto.WorkspaceDto;
import com.socialhub.workspace.entity.Workspace;
import com.socialhub.workspace.repository.WorkspaceRepository;
import com.socialhub.workspace.service.interfaces.WorkspaceService;

import com.socialhub.workspacemember.entity.WorkspaceMember;
import com.socialhub.workspacemember.entity.WorkspaceMemberId;
import com.socialhub.workspacemember.repository.WorkspaceMemberRepository;

import com.socialhub.user.repository.UserRepository;
import com.socialhub.security.AccessService;

@Service
@RequiredArgsConstructor
public class WorkspaceServiceImpl implements WorkspaceService {

    private final WorkspaceRepository repo;
    private final WorkspaceMemberRepository members;
    private final UserRepository users;
    private final AccessService access;

    private WorkspaceDto.View view(Workspace w) {
        return new WorkspaceDto.View(
                w.getId(),
                w.getName(),
                w.getCreatedAt()
        );
    }

    @Override
    @Transactional
    public WorkspaceDto.View create(WorkspaceDto.Create r) {

        UUID userId = access.userId();

        Workspace w = new Workspace();
        w.setName(r.name());

        repo.saveAndFlush(w);

        // PostgreSQL tự sinh created_at.
        // Refresh để lấy giá trị mới từ database.
        repo.flush();

        WorkspaceMember m = new WorkspaceMember();

        m.setId(new WorkspaceMemberId(w.getId(), userId));
        m.setWorkspace(w);
        m.setUser(users.getReferenceById(userId));
        m.setRole("OWNER");

        members.save(m);

        return view(w);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkspaceDto.View> list() {

        UUID userId = access.userId();

        return members.findByIdUserId(userId)
                .stream()
                .map(member -> view(member.getWorkspace()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WorkspaceDto.View detail(UUID id) {
        return view(access.workspace(id));
    }

    @Override
    @Transactional
    public WorkspaceDto.View rename(UUID id, WorkspaceDto.Create r) {

        access.admin(id);

        Workspace w = access.workspace(id);
        w.setName(r.name());

        return view(repo.save(w));
    }

    @Override
    @Transactional
    public void delete(UUID id) {

        // Giữ nguyên kiểm tra OWNER.
        access.owner(id);

        // Đồng bộ các thay đổi đang chờ trước khi xóa.
        repo.flush();

        // Để PostgreSQL xử lý ON DELETE CASCADE.
        int deleted = repo.deleteWorkspaceById(id);

        if (deleted == 0) {
            throw new IllegalArgumentException(
                    "Workspace không tồn tại: " + id
            );
        }
    }
}