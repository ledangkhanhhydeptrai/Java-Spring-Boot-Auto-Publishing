
package com.socialhub.excelimport.service.interfaces;

import com.socialhub.excelimport.dto.ExcelImportDto;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public interface ExcelImportService {

    ExcelImportDto.View importExcel(
            UUID workspaceId,
            MultipartFile file,
            UUID socialAccountId,
            LocalTime publishTime
    );

    List<ExcelImportDto.View> list(UUID workspaceId);

    ExcelImportDto.View detail(
            UUID workspaceId,
            UUID importId
    );
}
