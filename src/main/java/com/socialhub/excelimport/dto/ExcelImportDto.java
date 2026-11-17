
package com.socialhub.excelimport.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class ExcelImportDto {

    private ExcelImportDto() {
    }

    public record View(
            UUID id,
            UUID workspaceId,
            String fileName,
            String fileHash,
            Integer totalRows,
            Integer importedRows,
            Integer skippedRows,
            OffsetDateTime createdAt
    ) {
    }
}
