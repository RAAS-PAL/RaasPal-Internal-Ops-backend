package com.raaspal.robotrecommendation.kpi;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.CsatWorkbookFixtures.MonthSpec;
import com.raaspal.robotrecommendation.kpi.csat.CsatStream;
import com.raaspal.robotrecommendation.kpi.csat.DatabaseCsatWorkbookSource;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbookSource.WorkbookFile;
import com.raaspal.robotrecommendation.kpi.dto.CsatWorkbookHistoryEntry;
import com.raaspal.robotrecommendation.kpi.repository.KpiCsatWorkbookRepository;
import com.raaspal.robotrecommendation.kpi.service.CsatWorkbookUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upload path that replaced the S3 bucket: what is accepted, what is
 * refused before it can be stored, and what history does when a row is removed.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:kpi-test.properties")
@Transactional
class CsatWorkbookUploadServiceTest {

    @Autowired private CsatWorkbookUploadService uploads;
    @Autowired private KpiCsatWorkbookRepository repository;

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    private static byte[] pm(int responses) {
        return CsatWorkbookFixtures.workbook("Post-MA CSAT Survey",
                MonthSpec.of("Jan 2026", 200, responses, new int[]{90, 10, 0, 0, 0}));
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, null, bytes);
    }

    @Test
    void anUploadedWorkbookBecomesTheCurrentOneForItsSurvey() {
        CsatWorkbookUploadService.UploadResult result = uploads.upload(file("ma.xlsx", pm(100)), null, null);

        assertThat(result.stream()).isEqualTo("pm");
        assertThat(result.duplicate()).isFalse();

        List<WorkbookFile> current = new DatabaseCsatWorkbookSource(repository).list();
        assertThat(current).singleElement().extracting(WorkbookFile::name).isEqualTo("ma.xlsx");
    }

    /**
     * The survey is read from the sheet title, then the file name. A workbook
     * that says neither would be stored and then silently ignored by every read,
     * so it is refused while the person still has the file in front of them.
     */
    @Test
    void aWorkbookWhoseSurveyCannotBeToldIsRefused() {
        byte[] bytes = CsatWorkbookFixtures.workbook("Quarterly Feedback",
                MonthSpec.of("Jan 2026", 10, 5, new int[]{5, 0, 0, 0, 0}));

        assertThatThrownBy(() -> uploads.upload(file("feedback.xlsx", bytes), null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Could not tell which survey");

        assertThat(repository.count()).isZero();
    }

    @Test
    void aFileThatIsNotAnXlsxIsRefused() {
        assertThatThrownBy(() -> uploads.upload(file("survey.csv", "a,b\n1,2".getBytes()), null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(".xlsx");
    }

    /** A double-click should not leave two rows nobody can tell apart. */
    @Test
    void reUploadingTheCurrentFileIsAnsweredInsteadOfStoredAgain() {
        byte[] bytes = pm(100);
        uploads.upload(file("ma.xlsx", bytes), null, null);

        CsatWorkbookUploadService.UploadResult again = uploads.upload(file("ma.xlsx", bytes), null, null);

        assertThat(again.duplicate()).isTrue();
        assertThat(repository.count()).isEqualTo(1);
    }

    /** A genuinely different file for the same survey is a new version, not a replacement. */
    @Test
    void aChangedWorkbookIsKeptAlongsideTheOneItReplaced() {
        uploads.upload(file("ma-jan.xlsx", pm(100)), null, null);
        uploads.upload(file("ma-feb.xlsx", pm(120)), null, null);

        assertThat(repository.count()).isEqualTo(2);
        assertThat(new DatabaseCsatWorkbookSource(repository).list())
                .singleElement().extracting(WorkbookFile::name).isEqualTo("ma-feb.xlsx");

        List<CsatWorkbookHistoryEntry> history = uploads.history();
        assertThat(history).extracting(CsatWorkbookHistoryEntry::fileName)
                .containsExactly("ma-feb.xlsx", "ma-jan.xlsx");
        assertThat(history).extracting(CsatWorkbookHistoryEntry::current)
                .containsExactly(true, false);
    }

    /**
     * The point of deriving "current" rather than storing a flag: deleting the
     * current row promotes the one before it with nothing to fix up, which is
     * how a wrong upload is undone.
     */
    @Test
    void deletingTheCurrentWorkbookMakesThePreviousOneCurrentAgain() {
        uploads.upload(file("ma-jan.xlsx", pm(100)), null, null);
        CsatWorkbookUploadService.UploadResult wrong = uploads.upload(file("ma-feb.xlsx", pm(120)), null, null);

        uploads.delete(wrong.id());

        assertThat(new DatabaseCsatWorkbookSource(repository).list())
                .singleElement().extracting(WorkbookFile::name).isEqualTo("ma-jan.xlsx");
        assertThat(uploads.history()).singleElement()
                .extracting(CsatWorkbookHistoryEntry::current).isEqualTo(true);
    }

    @Test
    void theStoredFileComesBackByteForByte() {
        byte[] bytes = pm(100);
        CsatWorkbookUploadService.UploadResult saved = uploads.upload(file("ma.xlsx", bytes), null, null);

        CsatWorkbookUploadService.Download download = uploads.download(saved.id());

        assertThat(download.fileName()).isEqualTo("ma.xlsx");
        assertThat(download.content()).isEqualTo(bytes);
    }

    /** Different surveys coexist; each has its own current row. */
    @Test
    void eachSurveyKeepsItsOwnCurrentWorkbook() {
        uploads.upload(file("ma.xlsx", pm(100)), null, null);
        uploads.upload(file("install.xlsx", CsatWorkbookFixtures.workbook("Post-installation CSAT Survey",
                MonthSpec.of("Jan 2026", 4, 2, new int[]{1, 1, 0, 0, 0}))), null, null);

        assertThat(new DatabaseCsatWorkbookSource(repository).list()).hasSize(2);
        assertThat(repository.findCurrent()).extracting(w -> w.getStream())
                .containsExactlyInAnyOrder(CsatStream.PM, CsatStream.INSTALLATION);
    }
}
