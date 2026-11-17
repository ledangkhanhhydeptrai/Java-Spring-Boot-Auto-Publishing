
package com.socialhub.campaign.service.interfaces;

import com.socialhub.campaign.dto.CampaignDto;

import java.util.List;
import java.util.UUID;

public interface CampaignService {

    CampaignDto.View create(CampaignDto.Create request);

    List<CampaignDto.View> list(UUID workspaceId);

    CampaignDto.View detail(UUID campaignId);

    CampaignDto.View pause(UUID campaignId);

    CampaignDto.View resume(UUID campaignId);

    CampaignDto.Progress progress(UUID campaignId);

    CampaignDto.Progress addJob(
            UUID campaignId,
            CampaignDto.AddJob request
    );
}
