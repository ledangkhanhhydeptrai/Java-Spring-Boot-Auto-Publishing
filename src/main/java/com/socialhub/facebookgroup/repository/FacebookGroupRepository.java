package com.socialhub.facebookgroup.repository;

import org.springframework.data.jpa.repository.JpaRepository; import java.util.*; import com.socialhub.facebookgroup.entity.FacebookGroup;
public interface FacebookGroupRepository extends JpaRepository<FacebookGroup, UUID> { List<FacebookGroup> findByWorkspaceId(UUID workspaceId); }
