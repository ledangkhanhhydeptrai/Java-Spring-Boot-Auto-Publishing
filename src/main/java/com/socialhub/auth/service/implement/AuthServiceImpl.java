package com.socialhub.auth.service.implement;

import lombok.*;

import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import com.socialhub.auth.dto.AuthDto;
import com.socialhub.auth.service.interfaces.AuthService;
import com.socialhub.user.entity.User;
import com.socialhub.user.repository.UserRepository;
import com.socialhub.security.JwtService;
import com.socialhub.common.ApiException;

@Service

public class AuthServiceImpl implements AuthService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthServiceImpl(
            UserRepository users,
            PasswordEncoder encoder,
            JwtService jwt
    ) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @Transactional
    public AuthDto.Token register(AuthDto.Register r) {
        if (users.findByEmailIgnoreCase(r.email().trim()).isPresent())
            throw new ApiException(HttpStatus.CONFLICT, "Email already registered");
        User u = new User();
        u.email = r.email().trim().toLowerCase(Locale.ROOT);
        u.displayName = r.displayName();
        u.passwordHash = encoder.encode(r.password());
        users.save(u);
        return new AuthDto.Token(jwt.create(u), u.id, u.email, u.displayName);
    }

    public AuthDto.Token login(AuthDto.Login r) {
        User u = users.findByEmailIgnoreCase(r.email().trim()).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!encoder.matches(r.password(), u.passwordHash))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        return new AuthDto.Token(jwt.create(u), u.id, u.email, u.displayName);
    }
}
