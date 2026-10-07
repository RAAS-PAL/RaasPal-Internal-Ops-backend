package com.raaspal.robotrecommendation.casereport.aotsheet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What the simple setup fills in for people: the guessed columns. Made-up data. */
class AotSheetSetupHelpersTest {

    @Test
    void theFieldsAreGuessedFromTheHeadersLeftToRight() {
        AotSheetPreview.Suggested s = AotSheetSyncService.suggest(List.of(
                "n", "AOT Ticket no.", "Issue Date", "Status Case", "Raaspal_Jobcard", "Issue Date (2)", "Status"));

        assertThat(s.rowId()).isEqualTo("AOT Ticket no.");
        assertThat(s.openDate()).isEqualTo("Issue Date");
        assertThat(s.status()).isEqualTo("Status Case");
        assertThat(AotSheetSyncService.suggest(List.of("A", "B")).rowId()).isNull();
    }

}
