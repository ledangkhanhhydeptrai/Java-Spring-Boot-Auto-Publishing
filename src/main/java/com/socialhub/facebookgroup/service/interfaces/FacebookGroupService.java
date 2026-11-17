package com.socialhub.facebookgroup.service.interfaces;

import java.util.*; import com.socialhub.facebookgroup.dto.FacebookGroupDto;
public interface FacebookGroupService { FacebookGroupDto.View create(UUID workspaceId,FacebookGroupDto.Create r);List<FacebookGroupDto.View> list(UUID workspaceId);void delete(UUID workspaceId,UUID id); }
