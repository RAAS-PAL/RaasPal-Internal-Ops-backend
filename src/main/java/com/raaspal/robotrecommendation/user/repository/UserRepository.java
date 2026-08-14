package com.raaspal.robotrecommendation.user.repository;

import com.raaspal.robotrecommendation.common.enums.Role;
import com.raaspal.robotrecommendation.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /*
     * Email lookups are case-INSENSITIVE, and must stay that way.
     *
     * An email address is case-insensitive in practice — every mail provider treats
     * Damrongkiat.t@ and damrongkiat.t@ as one mailbox — but a Postgres `=` is not.
     * With an exact match, an account stored with a capital letter simply cannot be
     * found by anyone who types it in lower case, and the failure surfaces as
     * "invalid email or password", which sends you looking at the password.
     *
     * The same reasoning applies to existsByEmail: an exact check would happily
     * create Damrongkiat.t@ alongside damrongkiat.t@ as two separate accounts.
     */
    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByEmailIgnoreCaseAndIsActiveTrue(String email);

    boolean existsByEmailIgnoreCase(String email);

    Page<User> findAllByRole(Role role, Pageable pageable);

    Page<User> findAllByIsActiveTrue(Pageable pageable);

    /** Backs the last-admin guard in {@code UserService.update}. */
    long countByRoleAndIsActiveTrue(Role role);
}