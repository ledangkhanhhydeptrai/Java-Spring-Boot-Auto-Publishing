package com.socialhub.auth.service.interfaces;

import com.socialhub.auth.dto.AuthDto;
public interface AuthService { AuthDto.Token register(AuthDto.Register request); AuthDto.Token login(AuthDto.Login request); }
