package com.raaspal.robotrecommendation.robotunit;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.robotunit.entity.ContractDocument;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.repository.ContractDocumentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.service.ContractDocumentService;
import com.raaspal.robotrecommendation.robotunit.service.ContractDocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One PDF, several robots: what attach, replace and remove do to the deployments
 * and to the bucket, against a store that is a map.
 */
class ContractDocumentServiceTest {

    private static final byte[] PDF = "%PDF-1.7\nfake".getBytes(StandardCharsets.US_ASCII);

    private final DeploymentRepository deployments = mock(DeploymentRepository.class);
    private final ContractDocumentRepository documents = mock(ContractDocumentRepository.class);
    private final MapStore store = new MapStore();
    private final ContractDocumentService service = new ContractDocumentService(deployments, documents, store);

    private final CustomerProfile ifs = CustomerProfile.builder().id(UUID.randomUUID()).companyName("IFS").build();
    private final Deployment siamCenter = deployment(ifs, "2025-09-30", "2026-09-29");
    private final Deployment siamParagon = deployment(ifs, "2025-09-30", "2026-09-29");
    private final Deployment otherDates = deployment(ifs, "2026-01-01", "2026-12-31");

    private final List<ContractDocument> saved = new ArrayList<>();

    @BeforeEach
    void wire() {
        when(documents.save(any())).thenAnswer(inv -> {
            ContractDocument d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            saved.add(d);
            return d;
        });
        for (Deployment d : List.of(siamCenter, siamParagon, otherDates)) {
            when(deployments.findByRobotUnitIdAndIsActiveTrue(d.getRobotUnit().getId())).thenReturn(List.of(d));
        }
        when(deployments.findActiveOnSameContract(ifs.getId(), LocalDate.parse("2025-09-30"), LocalDate.parse("2026-09-29")))
                .thenReturn(List.of(siamCenter, siamParagon));
        // The count the service uses to decide whether a document is orphaned.
        when(deployments.countByContractDocumentId(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return List.of(siamCenter, siamParagon, otherDates).stream()
                    .filter(d -> d.getContractDocument() != null && d.getContractDocument().getId().equals(id))
                    .count();
        });
    }

    @Test
    void attachingToOneRobotCoversTheOthersOnTheSameContractWhenAsked() {
        ContractDocumentService.Attached attached = service.attach(
                siamCenter.getRobotUnit().getId(), "IFS contract 2025.pdf", "application/pdf", PDF, true, "branny");

        assertThat(attached.deploymentsLinked()).isEqualTo(2);
        assertThat(siamCenter.getContractDocument()).isSameAs(siamParagon.getContractDocument());
        assertThat(otherDates.getContractDocument()).isNull();
        assertThat(store.objects).hasSize(1);
        assertThat(store.objects.keySet().iterator().next()).startsWith("contracts/" + ifs.getId() + "/");
        assertThat(attached.document().fileName()).isEqualTo("IFS contract 2025.pdf");
        assertThat(attached.document().uploadedBy()).isEqualTo("branny");
    }

    @Test
    void attachingToOneRobotOnlyLeavesTheOthersAlone() {
        service.attach(siamCenter.getRobotUnit().getId(), "one.pdf", "application/pdf", PDF, false, "branny");

        assertThat(siamCenter.getContractDocument()).isNotNull();
        assertThat(siamParagon.getContractDocument()).isNull();
    }

    /** A replaced document that nothing points at any more leaves the bucket too. */
    @Test
    void replacingDropsTheOrphanedDocumentFromRowAndBucket() {
        service.attach(siamCenter.getRobotUnit().getId(), "v1.pdf", "application/pdf", PDF, false, "branny");
        String firstKey = siamCenter.getContractDocument().getStorageKey();

        service.attach(siamCenter.getRobotUnit().getId(), "v2.pdf", "application/pdf", PDF, false, "branny");

        assertThat(siamCenter.getContractDocument().getFileName()).isEqualTo("v2.pdf");
        assertThat(store.objects).doesNotContainKey(firstKey).hasSize(1);
        assertThat(store.deleted).containsExactly(firstKey);
    }

    /** Detaching one robot keeps the file for the others; the last detach deletes it. */
    @Test
    void removingKeepsTheFileUntilTheLastRobotLetsGo() {
        service.attach(siamCenter.getRobotUnit().getId(), "shared.pdf", "application/pdf", PDF, true, "branny");
        String key = siamCenter.getContractDocument().getStorageKey();

        service.remove(siamCenter.getRobotUnit().getId());
        assertThat(siamCenter.getContractDocument()).isNull();
        assertThat(siamParagon.getContractDocument()).isNotNull();
        assertThat(store.objects).containsKey(key);

        service.remove(siamParagon.getRobotUnit().getId());
        assertThat(store.objects).doesNotContainKey(key);
        assertThat(store.deleted).containsExactly(key);
    }

    @Test
    void onlyARealPdfIsAccepted() {
        UUID robot = siamCenter.getRobotUnit().getId();
        assertThatThrownBy(() -> service.attach(robot, "contract.pdf", "application/pdf",
                "PK this is a docx".getBytes(StandardCharsets.US_ASCII), false, "b"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("not a PDF");
        assertThatThrownBy(() -> service.attach(robot, "empty.pdf", "application/pdf", new byte[0], false, "b"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> service.attach(robot, "big.pdf", "application/pdf",
                new byte[(int) ContractDocumentService.MAX_BYTES + 1], false, "b"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("20 MB");
        assertThat(store.objects).isEmpty();
    }

    @Test
    void aRobotWithoutADocumentHasNoLink() {
        assertThatThrownBy(() -> service.temporaryUrl(siamCenter.getRobotUnit().getId()))
                .isInstanceOf(ResourceNotFoundException.class);

        service.attach(siamCenter.getRobotUnit().getId(), "c.pdf", "application/pdf", PDF, false, "b");
        assertThat(service.temporaryUrl(siamCenter.getRobotUnit().getId()))
                .startsWith("https://fake/").contains("c.pdf");
    }

    private static Deployment deployment(CustomerProfile customer, String start, String end) {
        return Deployment.builder()
                .id(UUID.randomUUID())
                .robotUnit(com.raaspal.robotrecommendation.robotunit.entity.RobotUnit.builder()
                        .id(UUID.randomUUID()).serialNumber("GS-" + UUID.randomUUID().toString().substring(0, 6)).build())
                .customerProfile(customer)
                .isActive(true)
                .contractStartDate(LocalDate.parse(start))
                .contractEndDate(LocalDate.parse(end))
                .build();
    }

    /** The bucket, as a map. */
    static class MapStore implements ContractDocumentStore {
        final Map<String, byte[]> objects = new HashMap<>();
        final List<String> deleted = new ArrayList<>();

        @Override
        public void put(String key, byte[] bytes, String contentType) {
            objects.put(key, bytes);
        }

        @Override
        public String temporaryUrl(String key, String downloadFileName) {
            return "https://fake/" + key + "?name=" + downloadFileName;
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
            deleted.add(key);
        }
    }
}
