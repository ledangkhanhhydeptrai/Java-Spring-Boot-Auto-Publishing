
package com.socialhub.excelimport.entity;

import com.socialhub.post.entity.Post;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "excel_import_rows")
@Getter
@Setter
@NoArgsConstructor
public class ExcelImportRow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_id", nullable = false)
    private ExcelImport excelImport;

    @Column(name = "source_row_number", nullable = false)
    private Integer sourceRowNumber;

    @Column(name = "source_publish_date")
    private LocalDate sourcePublishDate;

    @Column(name = "source_status", nullable = false)
    private String sourceStatus;

    @Column(name = "processing_status", nullable = false)
    private String processingStatus;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private Post post;

    @Column(
            name = "source_published_url",
            columnDefinition = "TEXT"
    )
    private String sourcePublishedUrl;

    @Column(
            name = "created_at",
            insertable = false,
            updatable = false
    )
    private OffsetDateTime createdAt;
}
