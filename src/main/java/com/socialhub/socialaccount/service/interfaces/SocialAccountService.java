package com.socialhub.socialaccount.service.interfaces;

import java.util.*; import com.socialhub.socialaccount.dto.SocialAccountDto;
public interface SocialAccountService { SocialAccountDto.View create(UUID workspaceId,SocialAccountDto.Create r);List<SocialAccountDto.View> list(UUID workspaceId);void delete(UUID workspaceId,UUID id); }
