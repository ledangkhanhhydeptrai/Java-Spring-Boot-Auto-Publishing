package com.socialhub.facebookoauth.repository;

import com.socialhub.facebookoauth.entity.FacebookPageConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FacebookPageConnectionRepository extends JpaRepository<FacebookPageConnection, UUID> {
    Optional<FacebookPageConnection> findBySocialAccountIdAndFacebookUserId(
            UUID socialAccountId, String facebookUserId);

    List<FacebookPageConnection> findBySocialAccountIdOrderByLastConnectedAtDescIdAsc(UUID socialAccountId);

    @Query("""
            select c from FacebookPageConnection c
            where c.socialAccount.id = :accountId
              and c.status = 'CONNECTED'
              and c.accessTokenEncrypted is not null
              and (c.tokenExpiresAt is null or c.tokenExpiresAt > CURRENT_TIMESTAMP)
            order by c.lastConnectedAt desc, c.id asc
            """)
    List<FacebookPageConnection> findEligible(@Param("accountId") UUID accountId);
}
