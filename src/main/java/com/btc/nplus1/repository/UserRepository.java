package com.btc.nplus1.repository;

import com.btc.nplus1.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
