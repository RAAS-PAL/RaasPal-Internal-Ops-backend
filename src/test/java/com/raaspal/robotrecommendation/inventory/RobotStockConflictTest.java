package com.raaspal.robotrecommendation.inventory;

import com.raaspal.robotrecommendation.common.exception.GlobalExceptionHandler;
import com.raaspal.robotrecommendation.inventory.controller.RobotStockController;
import com.raaspal.robotrecommendation.inventory.entity.RobotStockEntry;
import com.raaspal.robotrecommendation.inventory.repository.RobotStockEntryRepository;
import com.raaspal.robotrecommendation.inventory.service.RobotStockService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RobotStockConflictTest {
    @Test
    void uniqueIndexRaceIsARetryable409NotAServerError() throws Exception {
        var repository = mock(RobotStockEntryRepository.class);
        var em = mock(EntityManager.class);
        var source = RobotStockEntry.builder().id(UUID.randomUUID()).brand("Test").model("Robot").quantity(2).build();
        when(repository.findById(source.getId())).thenReturn(Optional.of(source));
        when(repository.lockModel(anyString(), anyString(), anyString())).thenReturn(List.of(source));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique identity race"));
        var service = new RobotStockService(repository, em);
        var mvc = MockMvcBuilders.standaloneSetup(new RobotStockController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/v1/inventory/robot-stock/" + source.getId() + "/move")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"toStatus\":\"DEMO\",\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("robot_stock.changed_retry"));
        verify(repository, never()).copyPartLinks(any(), any());
    }
}
