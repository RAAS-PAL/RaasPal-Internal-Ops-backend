package com.raaspal.robotrecommendation.pm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayApiClient;
import com.raaspal.robotrecommendation.pm.adapter.PmMondayWriter;
import com.raaspal.robotrecommendation.pm.adapter.PmMondayWriter.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/** Reading a visit's date and status cells, whose date arrives as JSON inside a string, and writing the date. */
class PmMondayWriterTest {

    private static final String BOARD = "2444194682";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MondayApiClient monday;
    private PmMondayWriter writer;

    @BeforeEach
    void setUp() {
        monday = mock(MondayApiClient.class);
        writer = new PmMondayWriter(monday, objectMapper);
    }

    private void mondayReturns(String board, String value) throws Exception {
        mondayReturns(board, value, "Planning");
    }

    private void mondayReturns(String board, String value, String status) throws Exception {
        String cell = value == null ? "null" : objectMapper.writeValueAsString(value);
        when(monday.execute(contains("items(ids"), anyMap())).thenReturn(objectMapper.readTree("""
                {"items":[{"id":"9001","board":{"id":"%s"},"column_values":[
                  {"id":"date","text":"","value":%s},
                  {"id":"status","text":"%s","value":"{\\"index\\":1}"}]}]}
                """.formatted(board, cell, status)));
    }

    @Test
    void readsTheDate() throws Exception {
        mondayReturns(BOARD, "{\"date\":\"2026-06-10\",\"changed_at\":\"2026-05-01T03:00:00.000Z\"}");

        assertThat(writer.read(BOARD, "9001", "date", "status"))
                .isEqualTo(new Snapshot(LocalDate.of(2026, 6, 10), null, "Planning"));
    }

    @Test
    void readsTheTimeWhenThereIsOne() throws Exception {
        mondayReturns(BOARD, "{\"date\":\"2026-06-10\",\"time\":\"09:30:00\"}");

        assertThat(writer.read(BOARD, "9001", "date", "status").time()).isEqualTo("09:30:00");
    }

    @Test
    void readsAnEmptyCellAsNoDate() throws Exception {
        mondayReturns(BOARD, null);

        assertThat(writer.read(BOARD, "9001", "date", "status")).isEqualTo(new Snapshot(null, null, "Planning"));
    }

    @Test
    void readsTheStatusAsMondayShowsIt() throws Exception {
        mondayReturns(BOARD, "{\"date\":\"2026-06-10\"}", "Done");

        assertThat(writer.read(BOARD, "9001", "date", "status").status()).isEqualTo("Done");
    }

    /** An id that lands on another board must never be written to. */
    @Test
    void refusesAnItemOnAnotherBoard() throws Exception {
        mondayReturns("123", "{\"date\":\"2026-06-10\"}");

        assertThatThrownBy(() -> writer.read(BOARD, "9001", "date", "status")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAnItemMondayNoLongerHas() throws Exception {
        when(monday.execute(anyString(), anyMap())).thenReturn(objectMapper.readTree("{\"items\":[]}"));

        assertThatThrownBy(() -> writer.read(BOARD, "9001", "date", "status")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesTheDateAndKeepsTheTime() throws Exception {
        writer.write(BOARD, "9001", "date", LocalDate.of(2026, 6, 17), "09:30:00");

        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(monday).execute(contains("change_column_value"), variables.capture());
        assertThat(variables.getValue()).containsEntry("board", BOARD).containsEntry("item", "9001")
                .containsEntry("col", "date");
        assertThat(objectMapper.readTree((String) variables.getValue().get("value")))
                .isEqualTo(objectMapper.readTree("{\"date\":\"2026-06-17\",\"time\":\"09:30:00\"}"));
    }

    /** Only an undo back to "no date" writes an empty value, which clears the cell. */
    @Test
    @SuppressWarnings("unchecked")
    void writesAnEmptyValueToClearTheDate() throws Exception {
        writer.write(BOARD, "9001", "date", null, "09:30:00");

        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(monday).execute(contains("change_column_value"), variables.capture());
        assertThat(variables.getValue().get("value")).isEqualTo("{}");
    }
}
