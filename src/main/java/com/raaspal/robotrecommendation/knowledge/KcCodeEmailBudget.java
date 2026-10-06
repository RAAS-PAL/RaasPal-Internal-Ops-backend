package com.raaspal.robotrecommendation.knowledge;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Database-backed company-wide budget. This key cannot be a normalized company email. */
@Service
public class KcCodeEmailBudget {
    static final String KEY = "__kc_code_email_budget__";
    private final EmailCodeRepository codes;
    private final EmailCodeSlot slots;
    private final TransactionTemplate reservation;
    private final int maxPerHour;
    private final Clock clock;

    @Autowired
    public KcCodeEmailBudget(EmailCodeRepository codes, EmailCodeSlot slots,
                             PlatformTransactionManager transactions,
                             @Value("${app.kc.codes.max-per-hour:60}") int maxPerHour) {
        this(codes, slots, transactions, maxPerHour, Clock.systemUTC());
    }

    KcCodeEmailBudget(EmailCodeRepository codes, EmailCodeSlot slots,
                      PlatformTransactionManager transactions, int maxPerHour, Clock clock) {
        if (maxPerHour < 1) throw new IllegalArgumentException("app.kc.codes.max-per-hour must be positive");
        this.codes = codes;
        this.slots = slots;
        this.maxPerHour = maxPerHour;
        this.clock = clock;
        this.reservation = new TransactionTemplate(transactions);
        this.reservation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void reserve(boolean sendsEmail) {
        // Initialization commits separately and safely handles concurrent first requests.
        try { slots.ensure(KEY); }
        catch (DataIntegrityViolationException ex) {
            if (!codes.existsById(KEY)) throw ex;
        }
        reservation.executeWithoutResult(status -> {
            EmailCode budget = codes.lockByEmail(KEY).orElseThrow();
            Instant now = clock.instant();
            Instant window = budget.getWindowStartedAt();
            boolean newWindow = window == null || !now.isBefore(window.plusSeconds(3600));
            if (!newWindow && budget.getSendCount() >= maxPerHour) {
                long seconds = Duration.between(now, window.plusSeconds(3600)).toSeconds() + 1;
                throw new KcAuthException("unavailable", 429, Math.max(1, seconds));
            }
            // Unknown/inactive resets check the same cap (no account enumeration),
            // but consume no quota because they never send anything.
            if (!sendsEmail) return;
            if (newWindow) {
                budget.setWindowStartedAt(now);
                budget.setSendCount(0);
            }
            budget.setSendCount(budget.getSendCount() + 1);
        });
        // Reservation is committed BEFORE SMTP, with no global lock held during I/O.
        // Keep it on SMTP errors or later rollback: delivery may already have occurred.
    }
}
