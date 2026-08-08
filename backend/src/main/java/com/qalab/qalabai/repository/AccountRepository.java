package com.qalab.qalabai.repository;

import com.qalab.qalabai.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findFirstByOrderByIdAsc();

    boolean existsByName(String name);
}
