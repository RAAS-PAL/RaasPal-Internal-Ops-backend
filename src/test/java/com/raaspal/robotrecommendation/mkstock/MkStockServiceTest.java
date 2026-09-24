package com.raaspal.robotrecommendation.mkstock;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.mkstock.dto.MkDtos.Dashboard;
import com.raaspal.robotrecommendation.mkstock.dto.MkDtos.MovementRequest;
import com.raaspal.robotrecommendation.mkstock.dto.MkDtos.MovementView;
import com.raaspal.robotrecommendation.mkstock.entity.MkSparePart;
import com.raaspal.robotrecommendation.mkstock.entity.MkStockMovement;
import com.raaspal.robotrecommendation.mkstock.repository.MkPartImageRepository;
import com.raaspal.robotrecommendation.mkstock.repository.MkSparePartRepository;
import com.raaspal.robotrecommendation.mkstock.repository.MkStockMovementRepository;
import com.raaspal.robotrecommendation.mkstock.service.MkStockService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MkStockServiceTest {

    private final MkSparePartRepository parts = mock(MkSparePartRepository.class);
    private final MkStockMovementRepository movements = mock(MkStockMovementRepository.class);
    private final MkPartImageRepository images = mock(MkPartImageRepository.class);
    private final MkStockService service = new MkStockService(parts, movements, images);

    private final UUID id = UUID.randomUUID();
    private final MkSparePart part = MkSparePart.builder().id(id).partNo("MK-001").name("Tray sensor")
            .unit("pcs").minLevel(3).quantityOnHand(5).createdBy("t").build();

    private void stubPart() {
        when(parts.findForUpdate(id)).thenReturn(Optional.of(part));
        when(movements.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(parts.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void stockInAddsAndStockOutTakesAwayWithTheRunningBalance() {
        stubPart();
        MovementView in = service.recordMovement(id, new MovementRequest("IN", 4, null, "PO-1", null), "staff");
        assertThat(in.quantityChange()).isEqualTo(4);
        assertThat(in.balanceAfter()).isEqualTo(9);

        MovementView out = service.recordMovement(id, new MovementRequest("OUT", 7, "Replaced at Siam branch", null, null), "staff");
        assertThat(out.quantityChange()).isEqualTo(-7);
        assertThat(out.balanceAfter()).isEqualTo(2);
        assertThat(part.getQuantityOnHand()).isEqualTo(2);
        assertThat(part.stockStatus()).isEqualTo("LOW");
    }

    @Test
    void everyStockOutNeedsAReason() {
        stubPart();
        assertThatThrownBy(() -> service.recordMovement(id, new MovementRequest("OUT", 1, "  ", null, null), "staff"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> service.recordMovement(id, new MovementRequest("ADJUST", -1, null, null, null), "staff"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("reason");
        verify(movements, never()).save(any());
    }

    @Test
    void stockCannotGoBelowZeroOrMoveWithoutAQuantity() {
        stubPart();
        assertThatThrownBy(() -> service.recordMovement(id, new MovementRequest("OUT", 6, "Broken", null, null), "staff"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Only 5");
        assertThatThrownBy(() -> service.recordMovement(id, new MovementRequest("ADJUST", -6, "Recount", null, null), "staff"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.recordMovement(id, new MovementRequest("IN", -2, null, null, null), "staff"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> service.recordMovement(id, new MovementRequest("IN", 0, null, null, null), "staff"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.recordMovement(id,
                new MovementRequest("IN", 1, null, null, LocalDate.now(MkStockService.BANGKOK).plusDays(1)), "staff"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("future");
        assertThat(part.getQuantityOnHand()).isEqualTo(5);
    }

    @Test
    void aRetiredPartCannotMove() {
        part.setActive(false);
        stubPart();
        assertThatThrownBy(() -> service.recordMovement(id, new MovementRequest("IN", 1, null, null, null), "staff"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("retired");
    }

    @Test
    void dashboardAddsUpInOutTopPartsAndReasons() {
        LocalDate d1 = LocalDate.of(2026, 9, 1);
        LocalDate d2 = LocalDate.of(2026, 9, 3);
        MkSparePart other = MkSparePart.builder().id(UUID.randomUUID()).partNo("MK-002").name("Battery")
                .unit("pcs").minLevel(0).quantityOnHand(0).createdBy("t").build();
        when(parts.findAllByOrderByActiveDescPartNoAsc()).thenReturn(List.of(part, other));
        when(movements.lastMovedPerPart()).thenReturn(List.of());
        when(movements.findTop15ByOrderByMovedOnDescCreatedAtDesc()).thenReturn(List.of());
        when(movements.findByMovedOnBetween(d1, d2)).thenReturn(List.of(
                mv(part, "IN", 10, d1, null),
                mv(part, "OUT", -3, d1, "Broken tray"),
                mv(part, "OUT", -2, d2, "broken tray "),
                mv(other, "OUT", -1, d2, "Branch swap"),
                mv(other, "ADJUST", 1, d2, "Recount")));

        Dashboard d = service.dashboard(d1, d2, true);

        assertThat(d.unitsIn()).isEqualTo(10);
        assertThat(d.unitsOut()).isEqualTo(6);
        assertThat(d.adjustments()).isEqualTo(1);
        assertThat(d.granularity()).isEqualTo("DAY");
        assertThat(d.series()).hasSize(3);
        assertThat(d.series().get(0).in()).isEqualTo(10);
        assertThat(d.series().get(0).out()).isEqualTo(3);
        assertThat(d.series().get(2).out()).isEqualTo(3);
        assertThat(d.topOut().get(0).partNo()).isEqualTo("MK-001");
        assertThat(d.topOut().get(0).units()).isEqualTo(5);
        // Reasons are grouped ignoring case and spaces.
        assertThat(d.outReasons().get(0).movements()).isEqualTo(2);
        assertThat(d.outReasons().get(0).units()).isEqualTo(5);
        assertThat(d.outOfStock()).isEqualTo(1);
        assertThat(d.attention()).extracting(p -> p.partNo()).containsExactly("MK-002");
    }

    @Test
    void longPeriodsAreGroupedByWeekOrMonth() {
        when(parts.findAllByOrderByActiveDescPartNoAsc()).thenReturn(List.of());
        when(movements.lastMovedPerPart()).thenReturn(List.of());
        when(movements.findTop15ByOrderByMovedOnDescCreatedAtDesc()).thenReturn(List.of());
        when(movements.findByMovedOnBetween(any(), any())).thenReturn(List.of());

        assertThat(service.dashboard(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), false).granularity()).isEqualTo("WEEK");
        assertThat(service.dashboard(LocalDate.of(2025, 9, 1), LocalDate.of(2026, 8, 31), false).granularity()).isEqualTo("MONTH");
        assertThatThrownBy(() -> service.dashboard(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1), false))
                .isInstanceOf(BadRequestException.class);
    }

    private static MkStockMovement mv(MkSparePart p, String type, int change, LocalDate on, String reason) {
        return MkStockMovement.builder().id(UUID.randomUUID()).partId(p.getId()).movementType(type)
                .quantityChange(change).balanceAfter(0).reason(reason).movedOn(on).createdBy("t").build();
    }

    @Test
    void aPhotoMustBeARealImageOfSensibleSize() {
        when(parts.findById(id)).thenReturn(Optional.of(part));
        when(parts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(movements.lastMovedPerPart()).thenReturn(List.of());

        assertThat(service.setImage(id, "data:image/jpeg;base64,/9j/4AAQ").hasImage()).isTrue();
        verify(images).save(any());
        assertThatThrownBy(() -> service.setImage(id, "https://example.com/x.jpg")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.setImage(id, "data:text/html;base64,PGgxPg==")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.setImage(id, "data:image/png;base64," + "A".repeat(1_400_000)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("too large");
        assertThat(service.removeImage(id).hasImage()).isFalse();
        verify(images).deleteById(id);
    }
}
