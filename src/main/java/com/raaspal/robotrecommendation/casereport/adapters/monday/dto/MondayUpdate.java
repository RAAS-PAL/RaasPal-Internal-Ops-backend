package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A comment on a board item. monday returns updates <strong>newest first</strong>,
 * so index 0 of {@link MondayItem#updates()} is the latest one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MondayUpdate(
        String id,
        @JsonProperty("text_body") String textBody,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        MondayCreator creator,
        /**
         * Replies to this comment, oldest first as monday returns them. Only the
         * filtered brand read asks for them; group reads that do not get null here.
         */
        List<MondayUpdate> replies
) {

    /** The author's display name, or {@code "unknown"} when monday omits it. */
    public String creatorName() {
        return creator == null || creator.name() == null ? "unknown" : creator.name();
    }
}
