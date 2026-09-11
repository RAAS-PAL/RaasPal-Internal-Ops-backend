package com.raaspal.robotrecommendation.pm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayColumnValue;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.pm.config.PmMondayProperties;
import com.raaspal.robotrecommendation.pm.entity.PmContract;
import com.raaspal.robotrecommendation.pm.entity.PmServiceLine;
import com.raaspal.robotrecommendation.pm.entity.PmStatusBucket;
import com.raaspal.robotrecommendation.pm.entity.PmVisit;
import com.raaspal.robotrecommendation.pm.service.PmItemMapper;
import com.raaspal.robotrecommendation.pm.service.ProvinceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Mapping monday rows onto the mirror, including the cells whose text loses structure. */
class PmItemMapperTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-11T10:00:00+07:00");

    private PmItemMapper mapper;
    private PmMondayProperties.Board board;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ProvinceResolver resolver = new ProvinceResolver(objectMapper);
        Method load = ProvinceResolver.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(resolver);
        mapper = new PmItemMapper(objectMapper, resolver);

        board = new PmMondayProperties.Board();
        board.setId("2048972900");
        board.setServiceLine(PmServiceLine.CLEANING);
        board.setSubitemBoardId("2444194682");
        board.getColumns().setProvince("province_col");
        board.getColumns().setRegion("region_col");
        board.getColumns().setLocation("location_col");
        board.getColumns().setWarrantyTimeline("timeline_col");
        board.getColumns().setRobotCount("count_col");
        board.getColumns().setSerials("sn1,sn2,sn3");
        board.getSubitemColumns().setPlanDate("plan_col");
        board.getSubitemColumns().setActionDate("action_col");
        board.getSubitemColumns().setStatus("status_col");
        board.getSubitemColumns().setOwner("owner_col");
    }

    private static MondayItem item(String id, String name, MondayColumnValue... cells) {
        return new MondayItem(id, name, NOW, null, List.of(cells), null, null);
    }

    private static MondayColumnValue text(String id, String text) {
        return new MondayColumnValue(id, "text", text, null);
    }

    private static MondayColumnValue json(String id, String text, String value) {
        return new MondayColumnValue(id, "location", text, value);
    }

    @Test
    void derivesRegionAndZoneFromProvinceAndKeepsMondaysOwnRegionOnlyForDiagnosis() {
        PmContract contract = mapper.toContract(
                item("1", "Some site",
                        text("province_col", "เชียงใหม่"),
                        // Deliberately one of the wrong spellings seen in the live board.
                        text("region_col", "ภาคใต้")),
                board, null, NOW);

        assertThat(contract.getProvinceResolved()).isEqualTo("เชียงใหม่");
        assertThat(contract.getRegionResolved()).isEqualTo("NORTH");
        assertThat(contract.getZoneResolved()).isEqualTo("UPPER_NORTH");
        assertThat(contract.getRegionRaw()).isEqualTo("ภาคใต้");
    }

    @Test
    void readsCoordinatesFromTheLocationValueNotItsText() {
        PmContract contract = mapper.toContract(
                item("1", "Some site",
                        json("location_col", "123 Sukhumvit Road, Bangkok",
                                "{\"lat\":\"13.7563\",\"lng\":\"100.5018\",\"address\":\"Bangkok\"}")),
                board, null, NOW);

        assertThat(contract.getLat()).isEqualByComparingTo("13.7563");
        assertThat(contract.getLng()).isEqualByComparingTo("100.5018");
    }

    @Test
    void readsWarrantyDatesFromTheTimelineValue() {
        PmContract contract = mapper.toContract(
                item("1", "Some site",
                        json("timeline_col", "2024-01-15 - 2026-01-14",
                                "{\"from\":\"2024-01-15\",\"to\":\"2026-01-14\"}")),
                board, null, NOW);

        assertThat(contract.getWarrantyStart()).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(contract.getWarrantyEnd()).isEqualTo(LocalDate.of(2026, 1, 14));
        assertThat(contract.getWarrantyText()).isEqualTo("2024-01-15 - 2026-01-14");
    }

    @Test
    void joinsTheSerialsSpreadAcrossSeveralColumns() {
        PmContract contract = mapper.toContract(
                item("1", "Some site",
                        text("sn1", "GS438-0001"),
                        text("sn3", "GS438-0003")),
                board, null, NOW);

        assertThat(contract.getRobotSerials()).isEqualTo("GS438-0001, GS438-0003");
    }

    @Test
    void keepsTheIdentityOfAContractItHasSeenBefore() {
        PmContract existing = mapper.toContract(item("77", "First read"), board, null, NOW);
        UUID originalId = existing.getId();
        OffsetDateTime firstSeen = existing.getFirstSeenAt();

        PmContract updated = mapper.toContract(
                item("77", "Renamed on the board"), board, existing, NOW.plusDays(1));

        assertThat(updated.getId()).isEqualTo(originalId);
        assertThat(updated.getFirstSeenAt()).isEqualTo(firstSeen);
        assertThat(updated.getItemName()).isEqualTo("Renamed on the board");
        assertThat(updated.getLastSyncedAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    void parsesThePmSequenceOutOfTheVisitName() {
        UUID contractId = UUID.randomUUID();
        assertThat(visit("PM4 : IFS : Siam Paragon M50", contractId).getPmSequence()).isEqualTo(4);
        assertThat(visit("PM 12 something", contractId).getPmSequence()).isEqualTo(12);
        assertThat(visit("MA02 legacy row", contractId).getPmSequence()).isEqualTo(2);
        assertThat(visit("Ad-hoc visit", contractId).getPmSequence()).isNull();
    }

    @Test
    void bucketsStatusAndReadsBothDates() {
        PmVisit done = mapper.toVisit(
                item("9", "PM1",
                        text("plan_col", "2026-09-14"),
                        text("action_col", "2026-09-15"),
                        text("status_col", "Done"),
                        text("owner_col", "Bright, Que")),
                board, UUID.randomUUID(), null, NOW);

        assertThat(done.getPlanDate()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(done.getActionDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(done.getStatusBucket()).isEqualTo(PmStatusBucket.COMPLETED);
        assertThat(done.getOwnerNames()).isEqualTo("Bright, Que");
    }

    /**
     * A visit with no plan date is the blind spot the planner counts, so it must
     * survive mapping rather than being dropped or dated to today.
     */
    @Test
    void keepsAVisitThatHasNoPlanDate() {
        PmVisit visit = mapper.toVisit(
                item("9", "PM3", text("status_col", "Planning")), board, UUID.randomUUID(), null, NOW);

        assertThat(visit.getPlanDate()).isNull();
        assertThat(visit.getStatusBucket()).isEqualTo(PmStatusBucket.PLANNED);
    }

    @Test
    void treatsAnUnreadableDateAsAbsentRatherThanFailingTheWholeSync() {
        PmVisit visit = mapper.toVisit(
                item("9", "PM3", text("plan_col", "soon")), board, UUID.randomUUID(), null, NOW);

        assertThat(visit.getPlanDate()).isNull();
    }

    /* ─── Company derivation ─────────────────────────────────────────────── */

    @Test
    void takesEverythingBeforeTheColonAsTheCompany() {
        assertThat(PmItemMapper.deriveCompany("PCS : Makro อุตรดิตถ์")).isEqualTo("PCS");
        assertThat(PmItemMapper.deriveCompany("ACS : BangSue Grand Station ( 1 )")).isEqualTo("ACS");
        assertThat(PmItemMapper.deriveCompany("IFS : โรงพยาบาลกรุงเทพ เชียงราย M50#5")).isEqualTo("IFS");
    }

    @Test
    void fallsBackToTheFirstWordWhenThereIsNoColon() {
        assertThat(PmItemMapper.deriveCompany("BBQ Gateway บางซื่อ")).isEqualTo("BBQ");
        assertThat(PmItemMapper.deriveCompany("MK CK5")).isEqualTo("MK");
    }

    /**
     * The case that makes the fallback worth writing: without skipping the legal-form
     * word, 32 unrelated sites file under "บริษัท", a bucket meaning "company".
     */
    @Test
    void skipsAThaiLegalFormPrefix() {
        assertThat(PmItemMapper.deriveCompany("บริษัท ไฮ-เทค แอพพาเรล จำกัด")).isEqualTo("ไฮ-เทค");
        assertThat(PmItemMapper.deriveCompany("หจก. สมชาย การช่าง")).isEqualTo("สมชาย");
    }

    /** Only skipped when it is a whole word - otherwise the name gets truncated to nothing real. */
    @Test
    void doesNotSplitAPrefixThatIsPartOfTheWord() {
        assertThat(PmItemMapper.deriveCompany("ร้านขนมบ้านยายกรณ์ จ.น่าน")).isEqualTo("ร้านขนมบ้านยายกรณ์");
    }

    @Test
    void collapsesStrayWhitespaceAndSurvivesEmptyNames() {
        assertThat(PmItemMapper.deriveCompany("  PCS   :  Makro น่าน ")).isEqualTo("PCS");
        assertThat(PmItemMapper.deriveCompany(null)).isNull();
        assertThat(PmItemMapper.deriveCompany("   ")).isNull();
    }

    /** A leading colon leaves no prefix to use, so the word fallback has to take over. */
    @Test
    void handlesANameThatStartsWithTheSeparator() {
        assertThat(PmItemMapper.deriveCompany(": Makro น่าน")).isEqualTo("Makro");
    }

    @Test
    void storesTheCompanyOnTheContract() {
        PmContract contract = mapper.toContract(item("1", "PCS : Makro พะเยา"), board, null, NOW);
        assertThat(contract.getCompany()).isEqualTo("PCS");
    }

    private PmVisit visit(String name, UUID contractId) {
        return mapper.toVisit(item("9", name), board, contractId, null, NOW);
    }
}
