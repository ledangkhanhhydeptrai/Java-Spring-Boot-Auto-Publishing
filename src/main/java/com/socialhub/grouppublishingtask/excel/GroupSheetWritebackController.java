package com.socialhub.grouppublishingtask.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.socialhub.common.ApiException;
import com.socialhub.security.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Group permalink -> SAME Google Sheet source row, separate column L.
 * Does not claim/submit posts or update shared G, Threads H, Page I, J or K.
 * Safe default: explicit preview, then explicit write. One target group per column.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/excel-imports/{importId}/group-writeback")
public class GroupSheetWritebackController {
    private static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets";
    private static final int MAX_BATCH = 10;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final AccessService access;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public record Request(UUID connectionId, UUID facebookGroupId, Integer fromRow,
                          Integer toRow, Integer limit, Boolean confirmWrite) { }
    public record Row(int sourceRowNumber, String status, String publishedUrl, String note) { }
    public record Report(boolean dryRun, int candidates, int written, int alreadySynced,
                         int blocked, List<Row> rows) { }
    private record Connection(UUID id, String spreadsheetId, String tabName,
                              String externalGroupId, boolean writebackEnabled) { }
    private record Candidate(int row, String postContent, String url, int distinctUrls) { }

    @PostMapping("/preview")
    public Report preview(@PathVariable UUID workspaceId, @PathVariable UUID importId,
                          @RequestBody Request req) {
        access.admin(workspaceId);
        Connection c = validate(workspaceId, importId, req);
        return execute(workspaceId, importId, c, req, true);
    }

    @PostMapping("/write")
    public Report write(@PathVariable UUID workspaceId, @PathVariable UUID importId,
                        @RequestBody Request req) {
        access.admin(workspaceId);
        Connection c = validate(workspaceId, importId, req);
        if (!Boolean.TRUE.equals(req.confirmWrite()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "confirmWrite=true required");
        if (!c.writebackEnabled())
            throw new ApiException(HttpStatus.CONFLICT, "Google Sheets connection writeback is disabled");
        return execute(workspaceId, importId, c, req, false);
    }

    // Downloadable CSV is a NEW report and does not alter the originally uploaded Excel file.
    @GetMapping(value="/export.csv", produces="text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> csv(@PathVariable UUID workspaceId, @PathVariable UUID importId,
                                      @RequestParam UUID facebookGroupId) {
        access.admin(workspaceId);
        Boolean allowed = db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM excel_imports e JOIN facebook_groups g
                   ON g.workspace_id=e.workspace_id WHERE e.id=? AND e.workspace_id=? AND g.id=?)
                """, Boolean.class, importId, workspaceId, facebookGroupId);
        if (!Boolean.TRUE.equals(allowed))
            throw new ApiException(HttpStatus.NOT_FOUND, "Import/Group not found in same workspace");
        List<Row> rows = db.query("""
                SELECT r.source_row_number, COALESCE(t.status, 'NOT_PLANNED') AS status,
                       COALESCE(t.published_url,'') AS published_url
                FROM excel_import_rows r
                JOIN excel_imports e ON e.id=r.import_id AND e.workspace_id=?
                LEFT JOIN LATERAL (
                    SELECT t.status, t.published_url
                    FROM group_publishing_tasks t
                    WHERE t.post_id=r.post_id AND t.facebook_group_id=?
                    ORDER BY t.published_at DESC NULLS LAST, t.created_at DESC LIMIT 1
                ) t ON TRUE
                WHERE r.import_id=? AND r.post_id IS NOT NULL
                ORDER BY r.source_row_number
                """, (rs, i) -> new Row(rs.getInt(1), rs.getString(2), rs.getString(3), ""),
                workspaceId, facebookGroupId, importId);
        StringBuilder csv = new StringBuilder("SourceRow,GroupTaskStatus,FacebookGroupPublishedURL\r\n");
        for (Row row : rows) csv.append(row.sourceRowNumber()).append(',')
                .append(escapeCsv(row.status())).append(',')
                .append(escapeCsv(row.publishedUrl())).append("\r\n");
        byte[] payload = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=facebook-group-links.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(payload);
    }

    private Connection validate(UUID workspaceId, UUID importId, Request req) {
        if (req == null || req.connectionId() == null || req.facebookGroupId() == null ||
                req.fromRow() == null || req.toRow() == null || req.limit() == null ||
                req.fromRow() < 2 || req.toRow() < req.fromRow() || req.toRow() > 10001 ||
                req.limit() < 1 || req.limit() > MAX_BATCH)
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "connectionId,facebookGroupId,fromRow,toRow,limit(1..10) required");
        List<Connection> connections = db.query("""
                SELECT c.id,c.spreadsheet_id,c.tab_name,g.external_group_id,c.writeback_enabled
                FROM google_sheet_connections c
                JOIN google_sheet_import_links l ON l.connection_id=c.id AND l.workspace_id=c.workspace_id
                JOIN excel_imports e ON e.id=l.import_id AND e.workspace_id=l.workspace_id
                JOIN facebook_groups g ON g.id=? AND g.workspace_id=e.workspace_id
                WHERE c.id=? AND c.workspace_id=? AND e.id=?
                  AND l.enabled=TRUE AND c.sync_status='SYNCED'
                """, (rs, n) -> new Connection(rs.getObject(1,UUID.class), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getBoolean(5)),
                req.facebookGroupId(), req.connectionId(), workspaceId, importId);
        if (connections.size() != 1)
            throw new ApiException(HttpStatus.CONFLICT,
                    "Enabled Google Sheet import link and same-workspace Facebook Group required");
        return connections.getFirst();
    }

    private Report execute(UUID workspaceId, UUID importId, Connection c, Request req, boolean dryRun) {
        List<Candidate> candidates = db.query("""
                SELECT r.source_row_number, p.content,
                       MIN(t.published_url) AS url, COUNT(DISTINCT t.published_url) AS distinct_urls
                FROM excel_import_rows r
                JOIN excel_imports e ON e.id=r.import_id AND e.workspace_id=?
                JOIN posts p ON p.id=r.post_id AND p.workspace_id=e.workspace_id
                JOIN group_publishing_tasks t ON t.post_id=p.id
                     AND t.facebook_group_id=? AND t.status='PUBLISHED'
                     AND t.published_url IS NOT NULL
                JOIN google_sheet_staged_rows s ON s.connection_id=?
                     AND s.source_row_number=r.source_row_number
                     AND s.present_in_sheet=TRUE AND s.content=p.content
                WHERE r.import_id=? AND r.source_row_number BETWEEN ? AND ?
                GROUP BY r.source_row_number,p.content
                ORDER BY r.source_row_number
                LIMIT ?
                """, (rs, n) -> new Candidate(rs.getInt(1), rs.getString(2),
                rs.getString(3), rs.getInt(4)), workspaceId, req.facebookGroupId(),
                c.id(), importId, req.fromRow(), req.toRow(), req.limit());
        int written=0, synced=0, blocked=0;
        List<Row> rows=new ArrayList<>();
        if (candidates.isEmpty()) return new Report(dryRun,0,0,0,0,List.of());
        final String token=oauthToken();
        final String requiredHeader="Link Facebook Group " + c.externalGroupId();
        // Fail closed if column L was already used for user data or another group.
        String header=cell(readCells(c,1,token),11);
        if (!header.isBlank() && !header.equals(requiredHeader))
            throw new ApiException(HttpStatus.CONFLICT,
                    "Column L has a different header; no Sheet cells written");
        for (Candidate item:candidates) {
            if (item.distinctUrls()!=1 || !validUrl(item.url(),c.externalGroupId())) {
                blocked++;
                rows.add(new Row(item.row(),"BLOCKED",item.url(),"Missing, invalid or conflicting permalink"));
                continue;
            }
            try {
                List<String> existing=readCells(c,item.row(),token);
                if (!cell(existing,1).equals(item.postContent())) {
                    blocked++;
                    rows.add(new Row(item.row(),"BLOCKED",item.url(),"Current Sheet B differs from imported Post"));
                    continue;
                }
                String current=cell(existing,11);
                if (item.url().equals(current)) {
                    synced++;
                    rows.add(new Row(item.row(),"ALREADY_SYNCED",item.url(),"Existing matching Group link"));
                    continue;
                }
                if (!current.isBlank()) {
                    blocked++;
                    rows.add(new Row(item.row(),"BLOCKED",item.url(),"Column L already contains different data"));
                    continue;
                }
                if (!dryRun) {
                    // Claim column L for this exact Group ID once; leave all other columns intact.
                    if (header.isBlank()) {
                        sendCell(c,"L1",requiredHeader,token);
                        header=requiredHeader;
                    }
                    sendCell(c,"L"+item.row(),item.url(),token);
                    written++;
                }
                rows.add(new Row(item.row(),dryRun?"WOULD_WRITE":"WRITTEN",item.url(),
                        "Only column L; G/H/I/J/K untouched"));
            } catch (Exception ex) {
                blocked++;
                rows.add(new Row(item.row(),"ERROR",item.url(),
                        ex.getClass().getSimpleName()+": "+safeMessage(ex)));
            }
        }
        return new Report(dryRun,candidates.size(),written,synced,blocked,List.copyOf(rows));
    }

    private List<String> readCells(Connection c,int row,String token) {
        String a1=tab(c)+"A"+row+":L"+row;
        String endpoint=base(c)+"/values/"+encode(a1)+"?valueRenderOption=FORMATTED_VALUE";
        try {
            HttpRequest req=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(12))
                    .header("Authorization","Bearer "+token).GET().build();
            HttpResponse<String> resp=http.send(req,HttpResponse.BodyHandlers.ofString());
            if(resp.statusCode()!=200) throw new IllegalStateException("Sheets read HTTP "+resp.statusCode());
            JsonNode root=mapper.readTree(resp.body()).path("values");
            List<String> cells=new ArrayList<>();
            if(root.isArray()&&!root.isEmpty()&&root.get(0).isArray())
                root.get(0).forEach(v->cells.add(v.asText("")));
            return cells;
        } catch(Exception e) { throw new IllegalStateException("Sheets read failed: "+safeMessage(e),e); }
    }

    private void sendCell(Connection c,String addr,String value,String token) throws Exception {
        String payload=mapper.writeValueAsString(Map.of("valueInputOption","RAW","data",
                List.of(Map.of("range",tab(c)+addr,"majorDimension","ROWS",
                        "values",List.of(List.of(value))))));
        HttpRequest req=HttpRequest.newBuilder(URI.create(base(c)+"/values:batchUpdate"))
                .timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+token)
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<String> res=http.send(req,HttpResponse.BodyHandlers.ofString());
        if(res.statusCode()!=200) throw new IllegalStateException("Sheets write HTTP "+res.statusCode());
    }

    private static String cell(List<String> arr,int index) {return index<arr.size()?arr.get(index):"";}
    private static String tab(Connection c) {return c.tabName()==null||c.tabName().isBlank()?"":
            "'"+c.tabName().replace("'","''")+"'!";}
    private static String base(Connection c) {return "https://sheets.googleapis.com/v4/spreadsheets/"+c.spreadsheetId();}
    private static String encode(String s) {return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20");}
    private static boolean validUrl(String url,String groupId) {
        if(url==null) return false;
        return url.matches("https://(?:www\\.)?facebook\\.com/groups/"+groupId+
                "/(?:posts|permalink)/(?:[0-9]+|pfbid[A-Za-z0-9]+)/?");
    }
    private static String escapeCsv(String text) {
        String s=text==null?"":text;
        if(s.matches("^[=+@-].*")) s="'"+s; // spreadsheet formula injection guard
        return "\""+s.replace("\"","\"\"")+"\"";
    }
    private static String safeMessage(Exception e) {
        String m=e.getMessage();return m==null?e.getClass().getSimpleName():m.substring(0,Math.min(160,m.length()));
    }
    private static String oauthToken() {
        try {
            GoogleCredentials c=GoogleCredentials.getApplicationDefault().createScoped(List.of(SCOPE));
            c.refreshIfExpired();if(c.getAccessToken()==null)c.refresh();
            return c.getAccessToken().getTokenValue();
        } catch(Exception e) {throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"Sheets credentials unavailable");}
    }
}
