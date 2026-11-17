package com.socialhub.user.repository;

import org.springframework.data.jpa.repository.JpaRepository; import java.util.*; import com.socialhub.user.entity.User;
public interface UserRepository extends JpaRepository<User, UUID> { Optional<User> findByEmailIgnoreCase(String email); }
