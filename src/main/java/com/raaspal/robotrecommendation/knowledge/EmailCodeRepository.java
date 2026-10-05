package com.raaspal.robotrecommendation.knowledge;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface EmailCodeRepository extends JpaRepository<EmailCode, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EmailCode c where c.email = :email")
    Optional<EmailCode> lockByEmail(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EmailCode c where c.ticketHash = :hash")
    Optional<EmailCode> lockByTicket(@Param("hash") String hash);
}
