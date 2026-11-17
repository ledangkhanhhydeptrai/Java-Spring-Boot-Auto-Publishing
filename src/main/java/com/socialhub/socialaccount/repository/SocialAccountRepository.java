
package com.socialhub.socialaccount.repository;

import com.socialhub.socialaccount.entity.SocialAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SocialAccountRepository
        extends JpaRepository<SocialAccount, UUID> {

    List<SocialAccount> findByWorkspaceId(UUID workspaceId);

    boolean existsByWorkspaceIdAndPlatformAndExternalAccountId(
            UUID workspaceId,
            String platform,
            String externalAccountId
    );

    Optional<SocialAccount> findByWorkspaceIdAndPlatformAndExternalAccountId(
            UUID workspaceId,
            String platform,
            String externalAccountId
    );

    List<SocialAccount> findByWorkspaceIdAndPlatform(
            UUID workspaceId,
            String platform
    );
}
