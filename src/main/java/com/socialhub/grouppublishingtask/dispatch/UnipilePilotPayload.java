package com.socialhub.grouppublishingtask.dispatch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Snapshot of DB text and one versioned media reference; not a hash of remote media bytes. */
final class UnipilePilotPayload {
    record Candidate(UUID postId, String externalGroupId, String text, String status, boolean due,
                     boolean approved, int mediaCount, String mediaType, String mediaUrl, String sha256) {
        boolean hasMedia() { return mediaCount > 0; }
        boolean eligibleText() { return approved && !hasMedia() && text != null && !text.isBlank(); }
        boolean eligibleMedia() { return approved && mediaCount == 1 && text != null
                && UnipileMediaResolver.allowedReference(mediaUrl,mediaType); }
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String snapshot(String text, int count, String type, String url) {
        if (count == 0) return hash(text == null ? "" : text);
        return hash("MEDIA-V1|"+count+"|"+frame(text)+frame(type)+frame(url));
    }
    private static String frame(String v) { return v == null ? "-1:" : v.length()+":"+v; }
    static List<Candidate> load(JdbcTemplate db, UUID workspace, UUID group, UUID task) {
        return db.query("""
                SELECT p.id,g.external_group_id,p.content,t.status,
                       COALESCE(t.scheduled_at<=now(),false),
                       COALESCE(p.approval_status='APPROVED' AND p.status IN ('READY','SCHEDULED','PUBLISHED'),false),
                       COALESCE(m.media_count,0),m.media_type,m.media_url
                FROM group_publishing_tasks t JOIN facebook_groups g ON g.id=t.facebook_group_id
                JOIN posts p ON p.id=t.post_id AND p.workspace_id=g.workspace_id
                LEFT JOIN LATERAL (SELECT count(*) OVER () AS media_count, media_type,
                          COALESCE(NULLIF(btrim(public_url),''),source_url) AS media_url
                          FROM post_media WHERE post_id=p.id ORDER BY display_order,id LIMIT 1) m ON true
                WHERE g.workspace_id=? AND g.id=? AND t.id=?
                """, (rs,n)-> {
                    String text=rs.getString(3), type=rs.getString(8), url=rs.getString(9);
                    int count=rs.getInt(7);
                    return new Candidate(rs.getObject(1,UUID.class),rs.getString(2),text,
                            rs.getString(4),rs.getBoolean(5),rs.getBoolean(6),count,type,url,
                            snapshot(text,count,type,url));
                },workspace,group,task);
    }
}
