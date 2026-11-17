
package com.socialhub.excelimport.repository;

import com.socialhub.excelimport.entity.ExcelImport;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExcelImportRepository
        extends JpaRepository<ExcelImport, UUID> {

    List<ExcelImport> findByWorkspaceIdOrderByCreatedAtDesc(
            UUID workspaceId
    );

    @EntityGraph(attributePaths = {"socialAccount"})
    Optional<ExcelImport> findByIdAndWorkspaceId(
            UUID id,
            UUID workspaceId
    );

    boolean existsByWorkspaceIdAndSocialAccountIdAndFileHash(
            UUID workspaceId,
            UUID socialAccountId,
            String fileHash
    );
}
