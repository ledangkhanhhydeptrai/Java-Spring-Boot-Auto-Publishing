package com.socialhub.workspace.controller;

import com.socialhub.workspace.dto.WorkspaceDto;
import com.socialhub.workspace.service.interfaces.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {
    private final WorkspaceService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceDto.View create(@Valid @RequestBody WorkspaceDto.Create request) {
        return service.create(request);
    }

    @GetMapping
    public List<WorkspaceDto.View> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public WorkspaceDto.View detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PutMapping("/{id}")
    public WorkspaceDto.View rename(@PathVariable UUID id, @Valid @RequestBody WorkspaceDto.Create request) {
        return service.rename(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
