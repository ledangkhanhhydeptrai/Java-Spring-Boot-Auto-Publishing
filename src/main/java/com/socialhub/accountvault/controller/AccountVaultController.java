package com.socialhub.accountvault.controller;

import com.socialhub.accountvault.service.AccountVaultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/account-vault")
public class AccountVaultController {
    private final AccountVaultService vault;

    @GetMapping("/accounts")
    public AccountVaultService.AccountPage accounts(
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String platform,
            @RequestParam(required=false) String status,
            @RequestParam(name="q", required=false) String query) {
        return vault.accounts(workspaceId, page, size, platform, status, query);
    }

    @GetMapping("/accounts/{accountId}")
    public AccountVaultService.AccountView account(@PathVariable UUID workspaceId,
                                                   @PathVariable UUID accountId) {
        return vault.account(workspaceId, accountId);
    }

    @GetMapping("/groups")
    public List<AccountVaultService.GroupView> groups(@PathVariable UUID workspaceId) {
        return vault.groups(workspaceId);
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountVaultService.GroupView createGroup(@PathVariable UUID workspaceId,
            @RequestBody AccountVaultService.GroupInput input) {
        return vault.createGroup(workspaceId, input);
    }

    @PutMapping("/groups/{groupId}")
    public AccountVaultService.GroupView updateGroup(@PathVariable UUID workspaceId,
            @PathVariable UUID groupId, @RequestBody AccountVaultService.GroupInput input) {
        return vault.updateGroup(workspaceId, groupId, input);
    }

    @DeleteMapping("/groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable UUID workspaceId, @PathVariable UUID groupId) {
        vault.deleteGroup(workspaceId, groupId);
    }

    @GetMapping("/groups/{groupId}/accounts")
    public List<AccountVaultService.AccountView> groupAccounts(@PathVariable UUID workspaceId,
                                                               @PathVariable UUID groupId) {
        return vault.groupAccounts(workspaceId, groupId);
    }

    @PutMapping("/groups/{groupId}/accounts/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addAccount(@PathVariable UUID workspaceId, @PathVariable UUID groupId,
                           @PathVariable UUID accountId) {
        vault.addAccount(workspaceId, groupId, accountId);
    }

    @DeleteMapping("/groups/{groupId}/accounts/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAccount(@PathVariable UUID workspaceId, @PathVariable UUID groupId,
                              @PathVariable UUID accountId) {
        vault.removeAccount(workspaceId, groupId, accountId);
    }
}
