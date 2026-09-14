package com.raaspal.robotrecommendation.casereport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.dto.CasePartsSummary;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the model's reply becomes four cells. Lenient by design: every malformed shape
 * degrades to blank cells, never to an exception, because a report that fails to
 * generate over one odd reply is worse than one row with dashes.
 */
class CasePartsSummaryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsACleanObject() {
        CasePartsSummary s = CasePartsSummary.parse("""
                {"required_part": "Potentiometer", "waiting": "รออะไหล่เสียคืนจาก AOTGA",
                 "waiting_from": "AOTGA", "part_received": "2026-07-22"}
                """, mapper);

        assertThat(s.requiredPart()).isEqualTo("Potentiometer");
        assertThat(s.waiting()).isEqualTo("รออะไหล่เสียคืนจาก AOTGA");
        assertThat(s.waitingFrom()).isEqualTo("AOTGA");
        assertThat(s.partReceived()).isEqualTo(LocalDate.of(2026, 7, 22));
        assertThat(s.isEmpty()).isFalse();
    }

    /** The prompt forbids fences and prose; the parser copes when the model adds them anyway. */
    @Test
    void toleratesAFenceAndProseAroundTheObject() {
        CasePartsSummary s = CasePartsSummary.parse("""
                Here is the JSON:
                ```json
                {"required_part": "4G Module", "waiting": null, "waiting_from": "Supplier RAASPAL", "part_received": null}
                ```
                """, mapper);

        assertThat(s.requiredPart()).isEqualTo("4G Module");
        assertThat(s.waiting()).isNull();
        assertThat(s.waitingFrom()).isEqualTo("Supplier RAASPAL");
        assertThat(s.partReceived()).isNull();
    }

    @Test
    void treatsEmptyStringsDashesAndTheWordNullAsBlank() {
        CasePartsSummary s = CasePartsSummary.parse(
                "{\"required_part\": \"\", \"waiting\": \"-\", \"waiting_from\": \"null\", \"part_received\": \" \"}",
                mapper);

        assertThat(s.isEmpty()).isTrue();
    }

    @Test
    void anUnparseableDateBecomesNullNotAnError() {
        CasePartsSummary s = CasePartsSummary.parse(
                "{\"required_part\": \"Cover\", \"part_received\": \"22/07/26\"}", mapper);

        assertThat(s.requiredPart()).isEqualTo("Cover");
        assertThat(s.partReceived()).isNull();
    }

    /** A timestamp is accepted for its date part. */
    @Test
    void acceptsADateTimeForItsDate() {
        CasePartsSummary s = CasePartsSummary.parse(
                "{\"part_received\": \"2026-09-05T10:00:00\"}", mapper);

        assertThat(s.partReceived()).isEqualTo(LocalDate.of(2026, 9, 5));
    }

    @Test
    void anythingWithoutAnObjectIsEmpty() {
        assertThat(CasePartsSummary.parse(null, mapper)).isEqualTo(CasePartsSummary.EMPTY);
        assertThat(CasePartsSummary.parse("", mapper)).isEqualTo(CasePartsSummary.EMPTY);
        assertThat(CasePartsSummary.parse("I cannot tell from this thread.", mapper)).isEqualTo(CasePartsSummary.EMPTY);
        assertThat(CasePartsSummary.parse("{not json", mapper)).isEqualTo(CasePartsSummary.EMPTY);
        assertThat(CasePartsSummary.parse("[1, 2]", mapper)).isEqualTo(CasePartsSummary.EMPTY);
    }
}
