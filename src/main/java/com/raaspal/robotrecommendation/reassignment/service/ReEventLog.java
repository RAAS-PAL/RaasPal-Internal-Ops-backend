package com.raaspal.robotrecommendation.reassignment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.reassignment.entity.ReEvent;
import com.raaspal.robotrecommendation.reassignment.repository.ReEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Writes the module's append-only audit trail ({@code re_event}). */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReEventLog {

    private final ReEventRepository events;
    private final ObjectMapper objectMapper;

    public void record(String entityType, Object entityId, String action, String actor, Map<String, ?> detail) {
        events.save(ReEvent.builder()
                .entityType(entityType)
                .entityId(String.valueOf(entityId))
                .action(action)
                .actor(actor)
                .detail(json(detail))
                .build());
    }

    public String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialise RE event detail: {}", e.getMessage());
            return "{}";
        }
    }
}
