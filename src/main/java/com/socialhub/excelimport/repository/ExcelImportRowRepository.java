
package com.socialhub.excelimport.repository;

import com.socialhub.excelimport.entity.ExcelImportRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExcelImportRowRepository
        extends JpaRepository<ExcelImportRow, UUID> {

    @EntityGraph(attributePaths = {"post"})
    List<ExcelImportRow> findByExcelImportIdOrderBySourceRowNumberAsc(
            UUID importId
    );

    @EntityGraph(attributePaths = {"excelImport", "excelImport.socialAccount"})
    Optional<ExcelImportRow> findByIdAndExcelImport_Workspace_Id(
            UUID id,
            UUID workspaceId
    );
}
