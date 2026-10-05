package com.raaspal.robotrecommendation.knowledge;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Creates an empty slot in its own transaction, so concurrent first sends can lock it. */
@Repository
@RequiredArgsConstructor
public class EmailCodeSlot {
    private final EntityManager em;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensure(String email) {
        if (em.find(EmailCode.class, email) != null) return;
        EmailCode slot = new EmailCode();
        slot.setEmail(email);
        slot.setPurpose(EmailCode.Purpose.SIGNUP);
        em.persist(slot);
        em.flush();
    }
}
