package com.socialhub.facebookgroup.service.implement;

import java.util.*;

import lombok.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import com.socialhub.facebookgroup.entity.FacebookGroup;
import com.socialhub.facebookgroup.repository.FacebookGroupRepository;
import com.socialhub.facebookgroup.dto.FacebookGroupDto;
import com.socialhub.facebookgroup.service.interfaces.FacebookGroupService;

@Service
@RequiredArgsConstructor
public class FacebookGroupServiceImpl implements FacebookGroupService {
    private final FacebookGroupRepository repo;
    private final AccessService access;

    private FacebookGroupDto.View view(FacebookGroup e) {
        return new FacebookGroupDto.View(e.id, e.externalGroupId, e.name, e.groupUrl);
    }

    @Transactional
    public FacebookGroupDto.View create(UUID workspaceId, FacebookGroupDto.Create r) {
        access.admin(workspaceId);
        FacebookGroup e = new FacebookGroup();
        e.workspace = access.workspace(workspaceId);
        e.externalGroupId = r.externalGroupId();
        e.name = r.name();
        e.groupUrl = r.groupUrl();
        return view(repo.save(e));
    }

    public List<FacebookGroupDto.View> list(UUID workspaceId) {
        access.workspace(workspaceId);
        return repo.findByWorkspaceId(workspaceId).stream().map(this::view).toList();
    }


    @Override
    @Transactional
    public void delete(UUID workspaceId, UUID id) {
        access.admin(workspaceId);

        FacebookGroup group = repo.findById(id)
                .orElseThrow(() ->
                        new ApiException(
                                HttpStatus.NOT_FOUND,
                                "Facebook Group not found"
                        )
                );

        if (!group.getWorkspace().getId().equals(workspaceId)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "Facebook Group not found"
            );
        }

        repo.delete(group);
        repo.flush();
    }

}
