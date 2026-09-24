package com.raaspal.robotrecommendation.mkstock;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.mkstock.dto.MkDtos.PinReset;
import com.raaspal.robotrecommendation.mkstock.entity.MkAccessPin;
import com.raaspal.robotrecommendation.mkstock.repository.MkAccessPinRepository;
import com.raaspal.robotrecommendation.mkstock.repository.MkViewSessionRepository;
import com.raaspal.robotrecommendation.mkstock.service.MkAccessService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MkAccessServiceTest {

    private final MkAccessPinRepository pins = mock(MkAccessPinRepository.class);
    private final MkViewSessionRepository sessions = mock(MkViewSessionRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final MkAccessService service = new MkAccessService(pins, sessions, encoder);

    @Test
    void resetMakesAStrongSixDigitPinAndStoresOnlyItsHash() {
        when(pins.findByActiveTrue()).thenReturn(Optional.empty());
        when(pins.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        PinReset reset = service.resetPin("admin@raaspal.com");

        assertThat(reset.pin()).matches("\\d{6}");
        assertThat(reset.pin().chars().distinct().count()).isGreaterThan(1);
        assertThat("0123456789").doesNotContain(reset.pin());
        ArgumentCaptor<MkAccessPin> saved = ArgumentCaptor.forClass(MkAccessPin.class);
        verify(pins).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPinHash()).isNotEqualTo(reset.pin());
        assertThat(encoder.matches(reset.pin(), saved.getValue().getPinHash())).isTrue();
        verify(sessions).deleteAllSessions();   // everyone at MK is signed out
    }

    @Test
    void resetRetiresTheOldPin() {
        MkAccessPin old = MkAccessPin.builder().pinHash("x").createdBy("a").build();
        when(pins.findByActiveTrue()).thenReturn(Optional.of(old));
        when(pins.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resetPin("admin@raaspal.com");

        assertThat(old.isActive()).isFalse();
        assertThat(old.getRevokedAt()).isNotNull();
    }

    @Test
    void aTypedPinStillRefusesEasyOnes() {
        assertThatThrownBy(() -> service.setPin("111111", "a")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.setPin("123456", "a")).isInstanceOf(BadRequestException.class);
    }
}
