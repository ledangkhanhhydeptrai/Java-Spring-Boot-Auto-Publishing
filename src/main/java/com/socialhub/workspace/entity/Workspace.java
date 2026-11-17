package com.socialhub.workspace.entity;

import jakarta.persistence.*;
import lombok.*;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "workspaces")
@Getter
@Setter
@NoArgsConstructor
public class Workspace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public String name;

    @Generated(event = EventType.INSERT)
    @Column(
            name = "created_at",
            insertable = false,
            updatable = false
    )
    public OffsetDateTime createdAt;
}