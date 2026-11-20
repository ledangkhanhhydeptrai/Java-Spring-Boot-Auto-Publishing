
package com.socialhub.excelimport.repository;

import com.socialhub.excelimport.entity.ExcelImportRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    @Query(value = """
            SELECT
                r.source_row_number AS sheetRow,
                r.post_id AS postId,
                COUNT(j.id) AS existingJobs,
                COUNT(j.id) FILTER (
                    WHERE j.status = 'PUBLISHED'
                ) AS publishedJobs
            FROM excel_import_rows r
            JOIN excel_imports e ON e.id = r.import_id
            JOIN posts p ON p.id = r.post_id
            LEFT JOIN publishing_jobs j
                ON j.post_id = r.post_id
               AND j.social_account_id = e.social_account_id
            WHERE e.workspace_id = :workspaceId
              AND r.import_id = :importId
              AND e.social_account_id = :socialAccountId
              AND r.source_row_number BETWEEN :fromRow AND :toRow
            GROUP BY r.source_row_number, r.post_id
            ORDER BY r.source_row_number
            """, nativeQuery = true)
    List<Object[]> findRepostCandidates(
            @Param("workspaceId") UUID workspaceId,
            @Param("importId") UUID importId,
            @Param("socialAccountId") UUID socialAccountId,
            @Param("fromRow") int fromRow,
            @Param("toRow") int toRow
    );
}
