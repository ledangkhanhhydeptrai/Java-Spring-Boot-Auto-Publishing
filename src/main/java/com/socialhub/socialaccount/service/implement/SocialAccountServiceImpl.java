package com.socialhub.socialaccount.service.implement;

import java.util.*;

import lombok.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import com.socialhub.socialaccount.entity.SocialAccount;
import com.socialhub.socialaccount.repository.SocialAccountRepository;
import com.socialhub.socialaccount.dto.SocialAccountDto;
import com.socialhub.socialaccount.service.interfaces.SocialAccountService;

@Service
@RequiredArgsConstructor
public class SocialAccountServiceImpl implements SocialAccountService {
    private final SocialAccountRepository repo;
    private final AccessService access;

    private SocialAccountDto.View view(SocialAccount e) {
        return new SocialAccountDto.View(e.id, e.platform, e.externalAccountId, e.displayName, e.status);
    }

    @Transactional
    public SocialAccountDto.View create(UUID workspaceId, SocialAccountDto.Create r) {
        access.admin(workspaceId);
        SocialAccount e = new SocialAccount();
        e.workspace = access.workspace(workspaceId);
        if (!List.of("FACEBOOK_PAGE", "THREADS").contains(r.platform()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported platform");
        e.platform = r.platform();
        e.externalAccountId = r.externalAccountId();
        e.displayName = r.displayName();
        e.status = "DISCONNECTED";
        return view(repo.save(e));
    }

    public List<SocialAccountDto.View> list(UUID workspaceId) {
        access.workspace(workspaceId);
        return repo.findByWorkspaceId(workspaceId).stream().map(this::view).toList();
    }

    @Override
    @Transactional
    public void delete(UUID workspaceId, UUID id) {
        access.admin(workspaceId);

        SocialAccount e = repo.findById(id)
                .orElseThrow(() ->
                        new ApiException(HttpStatus.NOT_FOUND, "Not found")
                );

        if (!e.getWorkspace().getId().equals(workspaceId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Not found");
        }

        repo.delete(e);
    }
}
