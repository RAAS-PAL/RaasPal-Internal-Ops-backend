package com.raaspal.robotrecommendation.cm.entity;

import com.raaspal.robotrecommendation.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A Corrective Maintenance report ("รายงานการซ่อมบำรุงแก้ไข") — the record of one
 * on-site repair visit, issued to the customer as a signed PDF.
 * <p>
 * Fields are extracted by AI from a pasted Monday.com ticket, then reviewed and
 * corrected by a human before the row is written; nothing here is trusted to be
 * AI-authored without review. Only {@link #customerName} is required — tickets
 * are routinely partial, and rejecting an incomplete one would send the
 * technician back to the Excel workflow this replaces.
 */
@Entity
@Table(name = "cm_reports")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CmReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Date of the service visit, printed as a Thai Buddhist-era date on the report. */
    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "ticket_no", length = 64)
    private String ticketNo;

    @Column(name = "customer_name", nullable = false, columnDefinition = "TEXT")
    private String customerName;

    /** Technician who performed the work ("เจ้าหน้าที่ผู้เข้าดำเนินการ"). */
    @Column(name = "technician_name", columnDefinition = "TEXT")
    private String technicianName;

    @Column(name = "robot_model")
    private String robotModel;

    @Column(name = "serial_number", length = 128)
    private String serialNumber;

    /** "รายละเอียดของสาเหตุ" — what the customer reported. */
    @Column(name = "cause_detail", columnDefinition = "TEXT")
    private String causeDetail;

    /** "ผลการตรวจสอบ" — what the technician found on inspection. */
    @Column(name = "inspection_result", columnDefinition = "TEXT")
    private String inspectionResult;

    /**
     * "การดำเนินการแก้ไข" — the repair steps, one per line. Stored unnumbered;
     * the numbering is applied when the report is rendered.
     */
    @Column(name = "corrective_actions", columnDefinition = "TEXT")
    private String correctiveActions;

    /** "ผลการทดสอบ" — the outcome after the repair. */
    @Column(name = "test_result", columnDefinition = "TEXT")
    private String testResult;

    /** The original paste, kept so a bad parse can be re-run without re-copying the ticket. */
    @Column(name = "source_text", columnDefinition = "TEXT")
    private String sourceText;

    /**
     * Signature photo for "ลงนามผู้ให้บริการ" (the RAASPAL technician), as a base64
     * {@code data:} URI. Inlined rather than stored via FileUploadService because
     * that writes to local disk and Render's disk is ephemeral — a redeploy would
     * silently break reprints. Null means the row prints blank to be signed on paper.
     */
    @Column(name = "provider_signature", columnDefinition = "TEXT")
    private String providerSignature;

    /** Signature photo for "ลงนามผู้รับบริการ" (the customer). See {@link #providerSignature}. */
    @Column(name = "receiver_signature", columnDefinition = "TEXT")
    private String receiverSignature;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
