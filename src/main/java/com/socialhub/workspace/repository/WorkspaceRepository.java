package com.socialhub.workspace.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

import com.socialhub.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {
    @Modifying
    @Query(
            value = "DELETE FROM workspaces WHERE id = :id",
            nativeQuery = true
    )
    int deleteWorkspaceById(@Param("id") UUID id);
}
