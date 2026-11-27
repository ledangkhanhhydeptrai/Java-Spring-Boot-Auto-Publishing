package com.socialhub.vietnamese;

import com.socialhub.response.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.List;

public class ViSmoke {
    record Demo(String status, String content, @JsonSerialize(using = ViDisplayTextSerializer.class) String note,
                @JsonSerialize(contentUsing = ViDisplayTextSerializer.class) List<String> errors) {
    }

    static int count;

    static void check(boolean v, String name) {
        if (!v) throw new AssertionError(name);
        count++;
    }

    public static void main(String[] args) throws Exception {
        check(ApiResponse.success("Success", null).message().equals("Thực hiện thành công."), "success");
        var e = ApiResponse.error(409, "Resolve overdue or uncertain Threads jobs before planning more");
        check(e.status() == 409 && e.data() == null && e.message().contains("Threads quá hạn"), "Threads conflict");
        check(ApiResponse.error(401, "Not authenticated").message().contains("đăng nhập lại"), "auth");
        check(ApiResponse.error(409, "Column L contains a different or invalid Group link at row 15").message().contains("Cột L"), "dynamic conflict");
        check(ApiResponse.error(500, "weird error abc").message().contains("Hệ thống"), "unknown");
        check(ApiResponse.error(400, "Vui lòng chọn bài viết.").message().equals("Vui lòng chọn bài viết."), "existing Vietnamese");
        check(ViApiMessages.validation("password", "Size", "size must be between 8 and 100").contains("8 đến 100"), "validation size");
        check(ViApiMessages.validation("email", "Email", "must be a well-formed email address").contains("email không hợp lệ"), "validation email");
        check(ViApiMessages.validation("workspaceId", "NotNull", "must not be null").equals("Vui lòng cung cấp không gian làm việc."), "validation label");
        check(ViApiMessages.detail("row 14: Post not found").contains("Dòng 14: Không tìm thấy bài viết"), "row detail");
        check(ViApiMessages.detail("PUBLISHED").equals("PUBLISHED"), "code unchanged");
        check(ViApiMessages.detail(null) == null, "null");
        var m = JsonMapper.builder().build();
        var d = new Demo("PENDING", "User English content", "Validation passed", List.of("Post not found"));
        var json = m.readTree(m.writeValueAsString(ApiResponse.success("Success", d)));
        check(json.path("status").asInt() == 200 && json.path("message").asString().equals("Thực hiện thành công."), "envelope JSON");
        check(json.path("data").path("status").asString().equals("PENDING"), "state JSON");
        check(json.path("data").path("content").asString().equals("User English content"), "content JSON");
        check(json.path("data").path("note").asString().equals("Dữ liệu hợp lệ."), "note JSON");
        check(json.path("data").path("errors").get(0).asString().equals("Không tìm thấy bài viết."), "list JSON");
        check(d.note().equals("Validation passed"), "original DTO preserved");
        var legacy = new com.fasterxml.jackson.databind.ObjectMapper().readTree(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(e));
        check(legacy.path("message").asText().contains("Threads quá hạn"), "Jackson2 security response");
        check(ViApiMessages.detail("KEEP_ORIGINAL; date from current synced Sheet (not historical original); CURRENT_SHEET:12").contains("không phải ngày gốc"), "date recovery warning");
        check(ViApiMessages.detail("MISSING_PUBLISH_TIME; EMPTY_CONTENT; NOT_APPROVED").contains("chưa được duyệt"), "multiple reasons");
        check(ViApiMessages.detail("Single status G (any platform); Threads H; Facebook I; Threads ID has no verified permalink; H preserved").contains("giữ nguyên cột H"), "writeback warning");
        check(ViApiMessages.detail("Unexpected provider response").equals("Chi tiết kỹ thuật: Unexpected provider response"), "unknown diagnostics retained");
        // Audit every discovered literal in the catalog and confirm exact lookup succeeds.
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(ViApiMessages.class.getResourceAsStream("/i18n/api-messages-vi.tsv"), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isBlank()) continue;
                var parts = line.split("\\t", 3);
                check(ViApiMessages.response(400, parts[1]).equals(parts[2]), "catalog: " + parts[1]);
            }
        }
        System.out.println(count + " checks passed");
    }
}
