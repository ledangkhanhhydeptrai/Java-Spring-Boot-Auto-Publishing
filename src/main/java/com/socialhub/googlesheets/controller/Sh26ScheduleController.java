package com.socialhub.googlesheets.controller;

import com.socialhub.googlesheets.dto.Sh26ScheduleDto;
import com.socialhub.googlesheets.service.Sh26ScheduleService;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/google-sheets/sh26/schedule")
public class Sh26ScheduleController {
    private final AccessService access;
    private final Sh26ScheduleService service;

    @GetMapping("/jobs")
    public List<Sh26ScheduleDto.JobStatus> jobs(@PathVariable UUID workspaceId) {
        access.admin(workspaceId);
        return service.jobs(workspaceId);
    }
    @GetMapping("/capabilities")
    public Sh26ScheduleDto.Capability capabilities(@PathVariable UUID workspaceId) {
        access.admin(workspaceId);
        return service.capabilities(workspaceId);
    }
    @PostMapping("/preview")
    public Sh26ScheduleDto.Preview preview(@PathVariable UUID workspaceId,
                                           @RequestBody Sh26ScheduleDto.Settings settings) {
        access.admin(workspaceId);
        return service.preview(workspaceId, settings);
    }
    @PostMapping("/confirm")
    public Sh26ScheduleDto.Result confirm(@PathVariable UUID workspaceId,
                                           @RequestBody Sh26ScheduleDto.Confirm request) {
        access.admin(workspaceId);
        return service.confirm(workspaceId, request);
    }
}
