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
