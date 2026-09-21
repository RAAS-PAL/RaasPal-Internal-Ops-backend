package com.raaspal.robotrecommendation.robotunit;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.robotunit.dto.RegisterRobotRequest;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import com.raaspal.robotrecommendation.robotunit.service.RobotUnitService;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.AutoxingFleetDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Registering AutoXing robots through the existing Tools -> Robots flow. */
class AutoxingRegistrationTest {

    private final RobotUnitRepository units = mock(RobotUnitRepository.class);
    private final DeploymentRepository deployments = mock(DeploymentRepository.class);
    private final CustomerProfileRepository customers = mock(CustomerProfileRepository.class);
    private final AutoxingFleetDirectory fleet = mock(AutoxingFleetDirectory.class);
    private final RobotUnitService service = new RobotUnitService(units, deployments, customers, fleet);

    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CustomerProfile customer = new CustomerProfile();
        customer.setId(customerId);
        customer.setCompanyName("KUBOTA");
        when(customers.findById(customerId)).thenReturn(Optional.of(customer));
        when(units.save(any(RobotUnit.class))).thenAnswer(inv -> inv.getArgument(0));
        when(deployments.save(any(Deployment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private RegisterRobotRequest request(String serial, String brand) {
        return new RegisterRobotRequest(serial, brand, "Zara L300", "Line 1", customerId, "Floor 1",
                null, null, null, null);
    }

    @Test
    void anAutoxingSerialTheFleetDoesNotKnowIsRefused() {
        when(fleet.knows("2382410c042997I")).thenReturn(Optional.of(false));

        assertThatThrownBy(() -> service.register(request("2382410c042997I", "AUTOXING")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("lowercase");
        verify(units, never()).save(any());
    }

    @Test
    void aKnownAutoxingSerialRegistersAsADeliveryRobot() {
        when(fleet.knows("2382410c042997l")).thenReturn(Optional.of(true));

        var response = service.register(request("2382410c042997l", "AUTOXING"));

        assertThat(response.robotType()).isEqualTo(RobotType.DELIVERY);
        assertThat(response.deployment().customerName()).isEqualTo("KUBOTA");
    }

    @Test
    void anAutoxingOutageDoesNotBlockRegistration() {
        when(fleet.knows(any())).thenReturn(Optional.empty());

        var response = service.register(request("2382410c042997l", "AUTOXING"));

        assertThat(response.serialNumber()).isEqualTo("2382410c042997l");
    }

    @Test
    void otherBrandsAreNeitherCheckedNorRetyped() {
        var response = service.register(request("GS-123", "GAUSIUM"));

        assertThat(response.robotType()).isEqualTo(RobotType.CLEANING);
        verifyNoInteractions(fleet);
    }
}
