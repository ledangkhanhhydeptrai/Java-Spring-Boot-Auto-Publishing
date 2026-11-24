package com.socialhub.encodingrepair;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/encoding-repair")
public class EncodingRepairController {
    private final EncodingRepairService service;

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EncodingRepairService.Preview preview(@PathVariable UUID workspaceId,
                                                  @RequestPart("file") MultipartFile file) {
        return service.preview(workspaceId, file);
    }

    @PostMapping(value = "/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EncodingRepairService.ApplyResult apply(@PathVariable UUID workspaceId,
                                                    @RequestPart("file") MultipartFile file,
                                                    @RequestParam String previewToken,
                                                    @RequestParam String confirmation) {
        return service.apply(workspaceId, file, previewToken, confirmation);
    }
}
