package com.raaspal.robotrecommendation.cm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.cm.dto.CmReportRequest;
import com.raaspal.robotrecommendation.cm.repository.CmReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Corrective Maintenance report API. Runs against {@code MockAiService} — the test
 * profile leaves {@code app.anthropic.api-key} unset, so the deterministic
 * label-scanning parser is the one under test here, not the live Claude call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser
class CmReportApiTest {

    private static final String BASE = "/api/v1/cm-reports";

    /** A realistic paste: the Pandora ticket this feature was built from. */
    private static final String TICKET = """
            วันที่ : 17 มิถุนายน 2569
            Ticket No. : 12152391009
            ชื่อบริษัทลูกค้า : Pandora : บริษัท แพนดอร่า โพรดักชั่น จำกัด
            เจ้าหน้าที่ผู้เข้าดำเนินการ : เอกภณ ส่งสุพร
            รุ่นหุ่นยนต์ : Phantas 1.3
            Serial Number : GS438-6260-B9R-V300
            รายละเอียดของสาเหตุ : หุ่นยนต์ทำความ Phantas ลูกค้าแจ้งว่าแขนหุ่นยนต์กดแล้วแขนไม่เด้ง ทำให้หุ่นยนต์ไม่ทำงาน
            ผลการตรวจสอบ : แขนหุ่นยนต์ กดแล้วแขนไม่เด้ง (ตัวล็อก Handheld หัก)
            การดำเนินการแก้ไข :
            1. ทำการถอดฝาครอบออก
            2. ดำเนินการเปลี่ยนตัวล็อกใหม่
            3. ทดสอบการทดงาน
            ผลการทดสอบ : หุ่นยนต์สามารถใช้งานได้ปกติ
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CmReportRepository cmReportRepository;

    @BeforeEach
    void setUp() {
        cmReportRepository.deleteAll();
    }

    private CmReportRequest sampleRequest() {
        return new CmReportRequest(
                LocalDate.of(2026, 6, 17),
                "12152391009",
                "Pandora : บริษัท แพนดอร่า โพรดักชั่น จำกัด",
                "เอกภณ ส่งสุพร",
                "Phantas 1.3",
                "GS438-6260-B9R-V300",
                "แขนหุ่นยนต์กดแล้วแขนไม่เด้ง",
                "ตัวล็อก Handheld หัก",
                "ทำการถอดฝาครอบออก\nดำเนินการเปลี่ยนตัวล็อกใหม่\nทดสอบการทดงาน",
                "หุ่นยนต์สามารถใช้งานได้ปกติ",
                TICKET,
                null,
                null);
    }

    private String createAndReturnId(CmReportRequest request) throws Exception {
        String body = mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asText();
    }

    /**
     * Parsing is a read-only preview step: it returns the extracted fields for the
     * operator to review and must not create a row. A parse the operator abandons
     * would otherwise litter the history.
     */
    @Test
    void parsingATicketReturnsADraftAndPersistsNothing() throws Exception {
        mockMvc.perform(post(BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("sourceText", TICKET))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketNo").value("12152391009"))
                .andExpect(jsonPath("$.data.robotModel").value("Phantas 1.3"))
                .andExpect(jsonPath("$.data.serialNumber").value("GS438-6260-B9R-V300"))
                .andExpect(jsonPath("$.data.correctiveActions.length()").value(3));

        assertThat(cmReportRepository.count()).isZero();
    }

    /**
     * The Thai date on the ticket is Buddhist-era ("2569"); the stored date must be
     * the Gregorian equivalent, or every saved report would be 543 years out.
     */
    @Test
    void parsingConvertsTheBuddhistEraDateToGregorian() throws Exception {
        mockMvc.perform(post(BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("sourceText", TICKET))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportDate").value("2026-06-17"));
    }

    /**
     * The numbering on the report is applied by the renderer, so the parser must
     * hand back bare steps — otherwise a saved report prints "1. 1. …".
     */
    @Test
    void parsingStripsTheLeadingNumberingFromCorrectiveActions() throws Exception {
        mockMvc.perform(post(BASE + "/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("sourceText", TICKET))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correctiveActions[0]").value("ทำการถอดฝาครอบออก"))
                .andExpect(jsonPath("$.data.correctiveActions[2]").value("ทดสอบการทดงาน"));
    }

    /**
     * The report is a signed customer document, so the Thai text must survive the
     * round-trip through the API and database byte-for-byte.
     */
    @Test
    void aSavedReportRoundTripsEveryFieldWithThaiTextIntact() throws Exception {
        String id = createAndReturnId(sampleRequest());

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportDate").value("2026-06-17"))
                .andExpect(jsonPath("$.data.ticketNo").value("12152391009"))
                .andExpect(jsonPath("$.data.customerName")
                        .value("Pandora : บริษัท แพนดอร่า โพรดักชั่น จำกัด"))
                .andExpect(jsonPath("$.data.technicianName").value("เอกภณ ส่งสุพร"))
                .andExpect(jsonPath("$.data.robotModel").value("Phantas 1.3"))
                .andExpect(jsonPath("$.data.serialNumber").value("GS438-6260-B9R-V300"))
                .andExpect(jsonPath("$.data.causeDetail").value("แขนหุ่นยนต์กดแล้วแขนไม่เด้ง"))
                .andExpect(jsonPath("$.data.inspectionResult").value("ตัวล็อก Handheld หัก"))
                .andExpect(jsonPath("$.data.testResult").value("หุ่นยนต์สามารถใช้งานได้ปกติ"));
    }

    /** Staff look a past report up by whichever identifier they happen to remember. */
    @Test
    void searchMatchesOnTicketNumberCustomerNameAndSerialNumber() throws Exception {
        createAndReturnId(sampleRequest());

        mockMvc.perform(get(BASE).param("q", "12152391009"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get(BASE).param("q", "Pandora"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get(BASE).param("q", "GS438-6260"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get(BASE).param("q", "no-such-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /**
     * The history list is rendered from summaries. Signature data URIs and the raw
     * paste dwarf every other field, so they are dropped until a report is opened.
     */
    @Test
    void theHistoryListOmitsSignaturesAndTheOriginalPaste() throws Exception {
        CmReportRequest withSignature = new CmReportRequest(
                LocalDate.of(2026, 6, 17), "T-1", "Pandora", null, null, null,
                null, null, null, null, TICKET,
                "data:image/png;base64,iVBORw0KGgo=", null);
        createAndReturnId(withSignature);

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].customerName").value("Pandora"))
                .andExpect(jsonPath("$.data[0].providerSignature").doesNotExist())
                .andExpect(jsonPath("$.data[0].sourceText").doesNotExist());
    }

    /** A report with no customer cannot be issued to anyone. */
    @Test
    void savingWithoutACustomerNameIsRejected() throws Exception {
        CmReportRequest blank = new CmReportRequest(
                LocalDate.of(2026, 6, 17), "T-1", "   ", null, null, null,
                null, null, null, null, null, null, null);

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blank)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * Signatures are stored inline in the row, so an un-resized phone photo would
     * bloat the table and slow every later read. The client downscales before
     * upload; this is the boundary that actually enforces it.
     */
    @Test
    void anOversizedSignatureImageIsRejected() throws Exception {
        String huge = "data:image/png;base64," + "A".repeat(512 * 1024);
        CmReportRequest request = new CmReportRequest(
                LocalDate.of(2026, 6, 17), "T-1", "Pandora", null, null, null,
                null, null, null, null, null, huge, null);

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    /** Only an image may be stored in a signature field. */
    @Test
    void aNonImageSignaturePayloadIsRejected() throws Exception {
        CmReportRequest request = new CmReportRequest(
                LocalDate.of(2026, 6, 17), "T-1", "Pandora", null, null, null,
                null, null, null, null, null,
                "data:text/html;base64,PHNjcmlwdD4=", null);

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    /** Requesting a report that does not exist is a 404, not an empty 200. */
    @Test
    void fetchingAnUnknownReportReturnsNotFound() throws Exception {
        mockMvc.perform(get(BASE + "/" + java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    /**
     * CM reports carry customer names and site details, and the endpoints declare no
     * matcher of their own — they rely entirely on SecurityConfig's
     * {@code anyRequest().authenticated()} catch-all. This pins that inheritance.
     */
    @Test
    @WithAnonymousUser
    void anonymousRequestsAreRejected() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized());
    }
}
