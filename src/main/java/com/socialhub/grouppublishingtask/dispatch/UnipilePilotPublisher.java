package com.socialhub.grouppublishingtask.dispatch;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class UnipilePilotPublisher implements GroupProductionPublisher {
    private final JdbcTemplate db;
    private final UnipilePilotClient client;
    final UUID workspace, group, task;
    final String externalGroup, approvedHash;
    private final boolean mediaMode;
    private final UnipileMediaResolver resolver = new UnipileMediaResolver();
    UnipilePilotPublisher(JdbcTemplate db, UnipilePilotClient client, UUID workspace, UUID group, UUID task,
                          String externalGroup, String approvedHash, boolean mediaMode) {
        if (!externalGroup.matches("[0-9]+") || !approvedHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Exact external group and approved SHA-256 required");
        this.db=db; this.client=client; this.workspace=workspace; this.group=group; this.task=task;
        this.externalGroup=externalGroup; this.approvedHash=approvedHash; this.mediaMode=mediaMode;
    }
    boolean matches(GroupDispatchEnvelope e) { return workspace.equals(e.workspaceId()) && group.equals(e.groupId()) && task.equals(e.taskId()); }
    @Override public boolean isGroupAuthorized(UUID w, UUID g) {
        // Operator attestation of account/group scope, not a remote permission introspection endpoint.
        return workspace.equals(w) && group.equals(g) && Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM facebook_groups WHERE workspace_id=? AND id=? AND external_group_id=?)",
                Boolean.class,w,g,externalGroup));
    }
    private UnipilePilotPayload.Candidate candidate() {
        var rows=UnipilePilotPayload.load(db,workspace,group,task);
        return rows.size()==1 ? rows.getFirst() : null;
    }
    private boolean eligible(UnipilePilotPayload.Candidate c,String state) {
        return c!=null && (mediaMode ? c.eligibleMedia() : c.eligibleText()) && c.due() && state.equals(c.status())
                && externalGroup.equals(c.externalGroupId()) && approvedHash.equals(c.sha256());
    }
    boolean ready() { return isGroupAuthorized(workspace,group) && eligible(candidate(),"READY"); }
    @Override public boolean isAuthorized(Request request) {
        if (!matches(request.envelope()) || !externalGroup.equals(request.externalGroupId())) return false;
        var c=candidate(); return eligible(c,"PROCESSING") && request.postId().equals(c.postId());
    }
    @Override public Result publish(Request request) {
        if (!matches(request.envelope()) || !externalGroup.equals(request.externalGroupId())) return new Result(Outcome.NEEDS_REVIEW,null);
        var c=candidate();
        if (!eligible(c,"PROCESSING") || !request.postId().equals(c.postId())) return new Result(Outcome.NEEDS_REVIEW,null);
        // Send this validated immutable string, not a later DB read.
        if (!mediaMode) return client.publish(externalGroup,c.text());
        try {
            var media=resolver.resolve(c.mediaUrl(),c.mediaType());
            // Recheck approval and reference after download. Never fall back to text on media failure.
            var current=candidate();
            if (!eligible(current,"PROCESSING") || !request.postId().equals(current.postId()))
                return new Result(Outcome.NEEDS_REVIEW,null);
            return client.publish(externalGroup,c.text(),media);
        } catch (java.io.IOException failure) { return new Result(Outcome.NEEDS_REVIEW,null); }
    }
}
