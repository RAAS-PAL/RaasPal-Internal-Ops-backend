package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading a pasted link, and laying the saved settings over the properties. */
class AotSheetSettingsServiceTest {

    private static final String ID = "1AbCdEfGhIjKlMnOpQrStUvWxYz_0123456789-abc";

    @Test
    void theIdComesOutOfAnyShapeOfLink() {
        assertThat(AotSheetSettingsService.spreadsheetIdFrom(
                "https://docs.google.com/spreadsheets/d/" + ID + "/edit#gid=0")).isEqualTo(ID);
        assertThat(AotSheetSettingsService.spreadsheetIdFrom(
                "https://docs.google.com/spreadsheets/u/1/d/" + ID + "/edit?usp=sharing")).isEqualTo(ID);
        assertThat(AotSheetSettingsService.spreadsheetIdFrom(ID)).isEqualTo(ID);
    }

    @Test
    void anythingElseIsRefused() {
        assertThatThrownBy(() -> AotSheetSettingsService.spreadsheetIdFrom("https://docs.google.com/document/d/x"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> AotSheetSettingsService.spreadsheetIdFrom("the AOT sheet"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void closedColoursAreStoredTidyAndATypoIsRefused() {
        assertThat(AotSheetSettingsService.closedColours(" #C9DAF8, c9daf8 ,#ffffff ")).isEqualTo("#c9daf8, #ffffff");
        assertThat(AotSheetSettingsService.closedColours("  ")).isNull();
        assertThatThrownBy(() -> AotSheetSettingsService.closedColours("#c9daf8, light blue"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("light blue");
    }

    @Test
    void savedColoursReachTheEffectiveSettings() {
        AotSheetSettingsService service = new AotSheetSettingsService(new AotSheetProperties(), null, null);

        AotSheetProperties effective = service.overlay(CaseSourceSheet.builder()
                .sourceKey(CaseSourceSheet.AOT).sheetUrl("x").spreadsheetId(ID).tab("Case").headerRow(1)
                .statusHeader("Status Case").closedColours("#c9daf8, #a4c2f4").build());

        assertThat(effective.getColourHeader()).isEmpty();
        assertThat(effective.colourColumn()).isEqualTo("Status Case");
        assertThat(effective.getClosedColours()).containsExactly("#c9daf8", "#a4c2f4");
        assertThat(effective.readsColour()).isTrue();
    }

    @Test
    void colourNamesKeepOnlyWhatSaysSomething() {
        List<AotSheetProperties.ColourLabel> kept = AotSheetSettingsService.colourLabels(List.of(
                new AotSheetProperties.ColourLabel("#FFFF00", "  Waiting for AOT "),
                new AotSheetProperties.ColourLabel("#ffffff", " "),          // no name
                new AotSheetProperties.ColourLabel("#c9daf8", "Done"),       // its stage says it
                new AotSheetProperties.ColourLabel("#F4CCCC", "ignored name", true), // means nothing
                AotSheetProperties.ColourLabel.ignore("#C9DAF8")),           // old part back wins
                "#c9daf8");

        assertThat(kept).containsExactly(new AotSheetProperties.ColourLabel("#ffff00", "Waiting for AOT"),
                AotSheetProperties.ColourLabel.ignore("#f4cccc"));
        assertThatThrownBy(() -> AotSheetSettingsService.colourLabels(List.of(
                new AotSheetProperties.ColourLabel("yellow", "x")), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void savedColourNamesReachTheEffectiveSettings() {
        AotSheetSettingsService service = new AotSheetSettingsService(new AotSheetProperties(), null, null);

        AotSheetProperties effective = service.overlay(CaseSourceSheet.builder()
                .sourceKey(CaseSourceSheet.AOT).sheetUrl("x").spreadsheetId(ID).tab("Case").headerRow(1)
                .colourLabels("[{\"colour\":\"#ffff00\",\"label\":\"Waiting for AOT\"},"
                        + "{\"colour\":\"#f4cccc\",\"label\":null,\"ignored\":true}]").build());

        assertThat(effective.getColourLabels()).containsExactly(
                new AotSheetProperties.ColourLabel("#ffff00", "Waiting for AOT"),
                AotSheetProperties.ColourLabel.ignore("#f4cccc"));
    }

    /** The saved row decides the sheet and columns; the descriptive column names stay. */
    @Test
    void savedSettingsWinOverTheProperties() {
        AotSheetProperties props = new AotSheetProperties();
        props.setSpreadsheetId("from-properties");
        props.setModelHeader("Robot model");
        AotSheetSettingsService service = new AotSheetSettingsService(props, null, null);

        AotSheetProperties effective = service.overlay(CaseSourceSheet.builder()
                .sourceKey(CaseSourceSheet.AOT).sheetUrl("x").spreadsheetId(ID).tab("Case").headerRow(1)
                .rowIdHeader("Case ID").statusHeader("Status").closedStatuses(" Closed, Done ,")
                .syncEnabled(true).build());

        assertThat(effective.getSpreadsheetId()).isEqualTo(ID);
        assertThat(effective.getRowIdHeader()).isEqualTo("Case ID");
        assertThat(effective.getClosedStatuses()).isEqualTo(List.of("Closed", "Done"));
        assertThat(effective.getCloseDateHeader()).isEmpty();
        assertThat(effective.getModelHeader()).isEqualTo("Robot model");
        assertThat(effective.isSyncEnabled()).isTrue();
        assertThat(effective.notReadyForSync()).isEmpty();
    }
}
