
package com.socialhub.excelimport.entity;

import com.socialhub.workspace.entity.Workspace;
import com.socialhub.socialaccount.entity.SocialAccount;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "excel_imports",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_excel_import_account_hash",
                        columnNames = {
                                "workspace_id",
                                "social_account_id",
                                "file_hash"
                        }
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ExcelImport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "social_account_id")
    private SocialAccount socialAccount;

    @Column(name = "publish_time")
    private LocalTime publishTime;

    @Column(name = "source_sheet_name")
    private String sourceSheetName;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_hash", nullable = false)
    private String fileHash;

    @Column(name = "total_rows", nullable = false)
    private Integer totalRows = 0;

    @Column(name = "imported_rows", nullable = false)
    private Integer importedRows = 0;

    @Column(name = "skipped_rows", nullable = false)
    private Integer skippedRows = 0;

    @Generated(event = EventType.INSERT)
    @Column(
            name = "created_at",
            insertable = false,
            updatable = false
    )
    private OffsetDateTime createdAt;
}
