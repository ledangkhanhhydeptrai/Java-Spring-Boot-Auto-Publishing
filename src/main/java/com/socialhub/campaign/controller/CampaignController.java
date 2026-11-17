
package com.socialhub.campaign.controller;

import com.socialhub.campaign.dto.CampaignDto;
import com.socialhub.campaign.service.interfaces.CampaignService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService campaignService;

    @PostMapping
    public ResponseEntity<CampaignDto.View> create(
            @Valid @RequestBody CampaignDto.Create request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(campaignService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<CampaignDto.View>> list(
            @RequestParam UUID workspaceId
    ) {
        return ResponseEntity.ok(
                campaignService.list(workspaceId)
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<CampaignDto.View> detail(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                campaignService.detail(id)
        );
    }

    @PostMapping("/{id}/jobs")
    public ResponseEntity<CampaignDto.Progress> addJob(
            @PathVariable UUID id,
            @Valid @RequestBody CampaignDto.AddJob request
    ) {
        return ResponseEntity.ok(
                campaignService.addJob(id, request)
        );
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<CampaignDto.View> pause(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                campaignService.pause(id)
        );
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<CampaignDto.View> resume(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                campaignService.resume(id)
        );
    }

    @GetMapping("/{id}/progress")
    public ResponseEntity<CampaignDto.Progress> progress(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                campaignService.progress(id)
        );
    }
}
