
package com.socialhub.campaign.repository;

import com.socialhub.campaign.entity.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CampaignRepository
        extends JpaRepository<Campaign, UUID> {

    List<Campaign> findByWorkspaceIdOrderByCreatedAtDesc(
            UUID workspaceId
    );
}
