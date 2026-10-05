package com.raaspal.robotrecommendation.knowledge;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

/** No toString: credentials must never end up in logs. */
@Entity
@Table(name = "email_codes")
@Getter @Setter @NoArgsConstructor
public class EmailCode {
    public enum Purpose { SIGNUP, RESET }
    @Id @Column(length = 255) private String email;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 6) private Purpose purpose;
    @Column(length = 100) private String codeHash;
    private Instant createdAt;
    private Instant expiresAt;
    @Column(nullable = false) private int tries;
    private Instant usedAt;
    private Instant windowStartedAt;
    @Column(nullable = false) private int sendCount;
    @Column(length = 64, unique = true) private String ticketHash;
    private Instant ticketExpiresAt;
    private Instant ticketUsedAt;
}
