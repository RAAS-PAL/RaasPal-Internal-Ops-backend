package com.raaspal.robotrecommendation.robotunit.dto;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Receive one or more robots into the warehouse.
 *
 * <p>Distinct from {@code RegisterRobotRequest}, which registers a robot <em>and</em>
 * deploys it to a customer in the same call — that path never produces stock. This
 * one produces stock and nothing else: no customer, no deployment.
 *
 * <p><strong>Why serials and not a quantity.</strong> A robot's serial is its identity
 * everywhere else in this system: telemetry sync, monthly reports, CM tickets and the
 * partner API all key on it. A stock level held as a bare number would have nothing
 * behind it, so deploying one of those robots would mean inventing a serial and
 * decrementing a count by hand — two steps that drift apart the first time someone
 * forgets the second. Taking the serials up front keeps one record per machine, and
 * the count is then derived and cannot be wrong.
 *
 * <p>Bulk is the normal case: a delivery of five arrives together, so the caller
 * sends five serials in one request rather than filling a form five times.
 */
public record ReceiveStockRequest(

        @NotBlank(message = "Brand is required")
        String brand,

        /** Free text as delivered ("Phantas v1.3"). Kept as provenance even when
         *  {@code robotId} resolves it to a catalogue model. */
        String model,

        /** CLEANING when omitted — every robot in the fleet today. */
        RobotType robotType,

        /** The catalogue model, when known. Null leaves the units unlinked, which is
         *  the same state 53 existing units are in; specs simply do not resolve yet. */
        UUID robotId,

        /** Which of our premises took delivery. */
        @Size(max = 128)
        String location,

        /**
         * One serial per robot. Duplicates within the request, and serials already
         * present in the fleet, are rejected as a group so a paste of five does not
         * half-apply.
         */
        @NotEmpty(message = "At least one serial number is required")
        List<@NotBlank(message = "Serial numbers cannot be blank") String> serialNumbers,

        /** Optional label applied to every unit in the batch, e.g. "Q3 delivery". */
        @Size(max = 255)
        String name,

        /**
         * IN_STOCK or DEMO. Defaults to IN_STOCK.
         *
         * <p>RENT and SOLD are refused: both describe a customer agreement, and a
         * receiving screen has no customer to attach one to.
         */
        RobotUnitStatus status
) {
}
