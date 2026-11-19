package com.socialhub.googlesheets.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public final class GoogleSheetsDto {
    private GoogleSheetsDto() {}

    public record PreviewRequest(
            @NotBlank String spreadsheetUrl,
            String range
    ) {}

    public record SheetRow(int rowNumber, List<String> cells) {}

    public record Preview(
            String spreadsheetId,
            String range,
            int returnedRows,
            List<SheetRow> rows
    ) {}
}
