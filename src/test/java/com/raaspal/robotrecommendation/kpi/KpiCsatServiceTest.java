package com.raaspal.robotrecommendation.kpi;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.CsatWorkbookFixtures.MonthSpec;
import com.raaspal.robotrecommendation.kpi.config.KpiCsatProperties;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbookParser;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbookSource;
import com.raaspal.robotrecommendation.kpi.csat.FolderCsatWorkbookSource;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse;
import com.raaspal.robotrecommendation.kpi.service.KpiCsatService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The combining rule and the cache, against an in-memory source. One sheet
 * shows its own Top Box cell; several sheets combine as the deck does — all
 * fives over all ratings.
 */
class KpiCsatServiceTest {

    private static final YearMonth JAN = YearMonth.of(2026, 1);
    private static final YearMonth FEB = YearMonth.of(2026, 2);

    /** A source whose files can be swapped between calls, like a folder the team writes to. */
    private static final class FakeSource implements CsatWorkbookSource {
        private final Map<String, byte[]> files = new LinkedHashMap<>();
        private final Map<String, Instant> modified = new LinkedHashMap<>();
        int listings;

        FakeSource put(String name, byte[] bytes, Instant lastModified) {
            files.put(name, bytes);
            modified.put(name, lastModified);
            return this;
        }

        @Override
        public String describe() {
            return "(memory)";
        }

        @Override
        public List<WorkbookFile> list() {
            listings++;
            List<WorkbookFile> out = new ArrayList<>();
            files.forEach((name, bytes) -> out.add(
                    new WorkbookFile(name, bytes.length, modified.get(name), () -> new ByteArrayInputStream(bytes))));
            return out;
        }
    }

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    private static KpiCsatService service(CsatWorkbookSource source) {
        return new KpiCsatService(source, new CsatWorkbookParser(), new KpiCsatProperties());
    }

    /** PM: 90 fives of 100 ratings. Installation: 1 of 2. Combined 91/102 = 89.2%; averaged 70%. */
    private static FakeSource pmAndInstall() {
        return new FakeSource()
                .put("ma.xlsx", CsatWorkbookFixtures.workbook("Post-MA CSAT Survey",
                        MonthSpec.of("Jan 2026", 200, 100, new int[]{90, 10, 0, 0, 0})), T0)
                .put("install.xlsx", CsatWorkbookFixtures.workbook("Post-installation CSAT Survey",
                        MonthSpec.of("Jan 2026", 4, 2, new int[]{1, 1, 0, 0, 0})), T0);
    }

    @Test
    void overallCombinesSurveysAsTheDeckDoes() {
        KpiCsatResponse r = service(pmAndInstall()).monthly(JAN, JAN);

        KpiCsatResponse.MonthCsat jan = r.months().get(0);
        assertThat(jan.pm().topBoxRate()).isEqualTo(90.0);
        assertThat(jan.installation().topBoxRate()).isEqualTo(50.0);
        assertThat(jan.overall().topBoxRate()).isEqualTo(89.2);   // 91 / 102, not (90 + 50) / 2
        assertThat(jan.overall().responses()).isEqualTo(102);
        assertThat(r.totals().overall().topBoxRate()).isEqualTo(89.2);
        assertThat(jan.cleaning().surveyed()).isFalse();
        assertThat(jan.cleaning().topBoxRate()).isNull();
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).contains("No workbook for the cleaning survey"));
    }

    /**
     * The real installation workbook: March's cell says 83.3% where its table
     * pools to 81.8%. For March alone the cell is shown. For Jan–Mar, which no
     * sheet holds, the sheets combine as the deck combines them.
     */
    @Test
    void oneSheetShowsItsCellAndARangeCombinesTheDecksWay() {
        FakeSource source = new FakeSource().put("install.xlsx", CsatWorkbookFixtures.workbook(
                "Post-installation CSAT Survey",
                MonthSpec.of("Jan 2026", 22, 9, new int[]{27, 18, 0, 0, 0}),                    // 27 of 45
                MonthSpec.of("Feb 2026", 9, 2, new int[]{10, 0, 0, 0, 0}),                      // 10 of 10
                MonthSpec.of("Mar 2026", 4, 3, new int[]{2, 1, 0, 0, 0}, new int[]{2, 0, 0, 0, 0},
                        new int[]{2, 0, 0, 0, 0}, new int[]{2, 1, 0, 0, 0}, new int[]{1, 0, 0, 0, 0})
                        .withTopBoxCell(0.8333)), T0);                                          // 9 of 11, cell 83.3%

        KpiCsatResponse r = service(source).monthly(JAN, YearMonth.of(2026, 3));

        assertThat(r.months().get(2).installation().topBoxRate()).isEqualTo(83.3);   // the cell, not 81.8
        assertThat(r.totals().installation().topBoxRate()).isEqualTo(69.7);          // (27+10+9) / (45+10+11)
    }

    /**
     * The console shows a reader where a figure came from, so the bucket says
     * which of the two it is and carries the counts behind a combined one.
     */
    @Test
    void aBucketSaysWhetherItsTopBoxIsACellOrWasCombined() {
        KpiCsatResponse r = service(pmAndInstall()).monthly(JAN, JAN);

        KpiCsatResponse.Bucket pm = r.months().get(0).pm();
        assertThat(pm.topBoxFromSheet()).isTrue();       // one survey, one month: the sheet's own cell
        assertThat(pm.fives()).isEqualTo(90);
        assertThat(pm.ratings()).isEqualTo(100);

        KpiCsatResponse.Bucket overall = r.months().get(0).overall();
        assertThat(overall.topBoxFromSheet()).isFalse(); // two surveys pooled; no sheet holds it
        assertThat(overall.fives()).isEqualTo(91);
        assertThat(overall.ratings()).isEqualTo(102);

        KpiCsatResponse.Bucket unsurveyed = r.months().get(0).cleaning();
        assertThat(unsurveyed.topBoxFromSheet()).isFalse();
        assertThat(unsurveyed.ratings()).isZero();
    }

    @Test
    void responseRateIsResponsesOverCustomersContacted() {
        KpiCsatResponse r = service(pmAndInstall()).monthly(JAN, JAN);

        assertThat(r.months().get(0).pm().responseRate()).isEqualTo(50.0);        // 100 of 200
        assertThat(r.months().get(0).overall().responseRate()).isEqualTo(50.0);   // 102 of 204
        assertThat(r.months().get(0).overall().notEvaluated()).isEqualTo(102);
    }

    @Test
    void aMonthNoWorkbookCoversIsNotSurveyedRatherThanZero() {
        KpiCsatResponse r = service(pmAndInstall()).monthly(JAN, FEB);

        assertThat(r.months()).hasSize(2);
        KpiCsatResponse.MonthCsat feb = r.months().get(1);
        assertThat(feb.month()).isEqualTo("2026-02");
        assertThat(feb.overall().surveyed()).isFalse();
        assertThat(feb.overall().topBoxRate()).isNull();
        assertThat(feb.overall().responseRate()).isNull();
        assertThat(r.asOf()).isEqualTo("2026-01");
        assertThat(r.totals().overall().topBoxRate()).isEqualTo(89.2);  // February adds nothing
    }

    @Test
    void theNewerOfTwoFilesForOneSurveyWinsAndIsReported() {
        FakeSource source = new FakeSource()
                .put("ma-old.xlsx", CsatWorkbookFixtures.workbook("Post-MA CSAT Survey",
                        MonthSpec.of("Jan 2026", 10, 10, new int[]{5, 5, 0, 0, 0})), T0)
                .put("ma-new.xlsx", CsatWorkbookFixtures.workbook("Post-MA CSAT Survey",
                        MonthSpec.of("Jan 2026", 10, 10, new int[]{10, 0, 0, 0, 0})), T0.plusSeconds(60));

        KpiCsatResponse r = service(source).monthly(JAN, JAN);

        assertThat(r.months().get(0).pm().topBoxRate()).isEqualTo(100.0);
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).contains("ma-old.xlsx").contains("using the newer, 'ma-new.xlsx'"));
        assertThat(r.sourceFiles()).extracting(KpiCsatResponse.SourceFile::name).containsExactly("ma-new.xlsx", "ma-old.xlsx");
    }

    @Test
    void aReplacedWorkbookIsNoticedOnTheNextRequestWithoutAReload() {
        FakeSource source = pmAndInstall();
        KpiCsatService service = service(source);
        assertThat(service.monthly(JAN, JAN).months().get(0).pm().topBoxRate()).isEqualTo(90.0);
        int parsedOnce = source.listings;

        // Same request again: listed, not re-parsed (the signature is unchanged).
        service.monthly(JAN, JAN);
        assertThat(source.listings).isEqualTo(parsedOnce + 1);

        // The team drops in this month's file: same name, new bytes, new timestamp.
        source.put("ma.xlsx", CsatWorkbookFixtures.workbook("Post-MA CSAT Survey",
                MonthSpec.of("Jan 2026", 200, 100, new int[]{100, 0, 0, 0, 0})), T0.plusSeconds(3600));

        assertThat(service.monthly(JAN, JAN).months().get(0).pm().topBoxRate()).isEqualTo(100.0);
    }

    @Test
    void reloadReportsWhatTheSourceHolds() {
        KpiCsatService.SourceStatus status = service(pmAndInstall()).reload();

        assertThat(status.source()).isEqualTo("(memory)");
        assertThat(status.surveys()).containsExactlyInAnyOrder("installation", "pm");
        assertThat(status.asOf()).isEqualTo("2026-01");
        assertThat(status.files()).hasSize(2);
        assertThat(status.files().get(0).firstMonth()).isEqualTo("2026-01");
        assertThat(status.loadedAt()).isNotNull();
    }

    @Test
    void theRangeIsValidatedLikeTheCaseKpis() {
        KpiCsatService service = service(pmAndInstall());

        assertThatThrownBy(() -> service.monthly(FEB, JAN))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("'to' must not be before 'from'");
        assertThatThrownBy(() -> service.monthly(JAN, JAN.plusMonths(KpiCsatService.MAX_MONTHS)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("must not exceed");
    }

    @Test
    void anUnconfiguredFolderIsA400ThatNamesTheEnvVar() {
        KpiCsatService service = service(new FolderCsatWorkbookSource(new KpiCsatProperties()));

        assertThatThrownBy(() -> service.monthly(JAN, JAN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("KPI_CSAT_FOLDER");
    }

    @Test
    void aMissingFolderSaysWhichOne() {
        KpiCsatProperties properties = new KpiCsatProperties();
        properties.setFolder("/nonexistent/csat-folder");

        assertThatThrownBy(() -> service(new FolderCsatWorkbookSource(properties)).monthly(JAN, JAN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not exist")
                .hasMessageContaining("csat-folder");
    }
}
