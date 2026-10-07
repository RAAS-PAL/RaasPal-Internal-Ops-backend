package com.raaspal.robotrecommendation.casereport.adapters.googlesheet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading background colours out of a spreadsheets.get answer, shaped as Google sends it. */
class GoogleSheetColoursTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void eachRowGetsItsColourByTheRowNumberTheSheetShows() throws Exception {
        JsonNode body = json.readTree("""
                {"sheets":[{"data":[{"startRow":1,"rowData":[
                  {"values":[{"effectiveFormat":{"backgroundColor":{"red":0.7882353,"green":0.85490197,"blue":0.972549}}}]},
                  {"values":[{"effectiveFormat":{"backgroundColor":{"red":1,"green":1,"blue":1}}}]},
                  {},
                  {"values":[{"userEnteredFormat":{"backgroundColor":{"red":0.7882353,"green":0.85490197,"blue":0.972549}}}]}
                ]}]}]}
                """);

        Map<Integer, String> colours = GoogleSheetApiClient.coloursByRow(body, 2);

        // startRow is 0-based: 1 is the sheet's row 2.
        assertThat(colours).containsExactly(
                Map.entry(2, "#c9daf8"),
                Map.entry(3, "#ffffff"),
                Map.entry(4, "#ffffff"),   // a row with nothing in it: no fill
                Map.entry(5, "#c9daf8"));  // empty cell: its own fill stands in
    }

    /** Google leaves a channel out when it is 0, so pure blue is just {"blue": 1}. */
    @Test
    void aMissingChannelIsZeroButAMissingColourIsWhite() throws Exception {
        assertThat(GoogleSheetApiClient.hex(json.readTree("{\"backgroundColor\":{\"blue\":1}}"))).isEqualTo("#0000ff");
        assertThat(GoogleSheetApiClient.hex(json.readTree("{}"))).isEqualTo("#ffffff");
        assertThat(GoogleSheetApiClient.hex(json.readTree(
                "{\"backgroundColorStyle\":{\"rgbColor\":{\"red\":1}},\"backgroundColor\":{\"blue\":1}}")))
                .isEqualTo("#ff0000");
    }

    @Test
    void withoutStartRowTheFirstRowIsTheOneAskedFor() throws Exception {
        JsonNode body = json.readTree("""
                {"sheets":[{"data":[{"rowData":[{"values":[{"effectiveFormat":{"backgroundColor":{"red":1,"green":1,"blue":1}}}]}]}]}]}
                """);

        assertThat(GoogleSheetApiClient.coloursByRow(body, 7)).containsExactly(Map.entry(7, "#ffffff"));
    }
}
