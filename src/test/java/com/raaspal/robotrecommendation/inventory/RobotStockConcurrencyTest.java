package com.raaspal.robotrecommendation.inventory;

import com.raaspal.robotrecommendation.inventory.dto.RobotStockMoveRequest;
import com.raaspal.robotrecommendation.inventory.dto.RobotStockUnitsRequest;
import com.raaspal.robotrecommendation.inventory.entity.RobotStockEntry;
import com.raaspal.robotrecommendation.inventory.repository.RobotStockEntryRepository;
import com.raaspal.robotrecommendation.inventory.service.RobotStockService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** No test transaction: separate threads must see real commits and contend on real locks. */
@SpringBootTest
class RobotStockConcurrencyTest {
    @Autowired private RobotStockService stock;
    @Autowired private RobotStockEntryRepository robots;
    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    private final String model = UUID.randomUUID().toString();

    @AfterEach
    void cleanup() {
        // Only this test's rows; other test fixtures sharing the H2 context survive.
        robots.deleteAll(robots.findModel("Concurrency test", model, ""));
    }

    @Test
    void concurrentAddsIntoMissingStatusCreateOneRowWithoutLosingEitherIncrement() throws Exception {
        var source = robots.saveAndFlush(RobotStockEntry.builder().brand("Concurrency test").model(model)
                .quantity(4).build());
        together(() -> stock.addUnits(source.getId(), new RobotStockUnitsRequest(DEMO, 2), null),
                () -> stock.addUnits(source.getId(), new RobotStockUnitsRequest(DEMO, 3), null));
        var rows = robots.findModel("Concurrency test", model, "");
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(r -> r.getStatus() == DEMO).findFirst().orElseThrow().getQuantity()).isEqualTo(5);
        assertThat(rows.stream().mapToInt(RobotStockEntry::getQuantity).sum()).isEqualTo(9);
    }

    @Test
    void oppositeMovesCompleteWithoutDeadlockOrLostUnits() throws Exception {
        var first = robots.saveAndFlush(RobotStockEntry.builder().brand("Concurrency test").model(model)
                .status(IN_STOCK).quantity(5).build());
        var second = robots.saveAndFlush(RobotStockEntry.builder().brand("Concurrency test").model(model)
                .status(DEMO).quantity(5).build());
        together(() -> stock.move(first.getId(), new RobotStockMoveRequest(DEMO, 2), null),
                () -> stock.move(second.getId(), new RobotStockMoveRequest(IN_STOCK, 3), null));
        assertThat(robots.findById(first.getId()).orElseThrow().getQuantity()).isEqualTo(6);
        assertThat(robots.findById(second.getId()).orElseThrow().getQuantity()).isEqualTo(4);
    }

    @Test
    void failureAfterTargetInsertRollsBackTheWholeSave() {
        var source = robots.saveAndFlush(RobotStockEntry.builder().brand("Concurrency test").model(model)
                .quantity(2).build());
        // Real H2 insert/flush, then a forced link-copy failure. Checking outside the
        // transaction proves that an early flush cannot leave an orphan shelf behind.
        var failingRepository = mock(RobotStockEntryRepository.class, delegatesTo(robots));
        doThrow(new DataIntegrityViolationException("link-copy failure"))
                .when(failingRepository).copyPartLinks(any(), any());
        var failingService = new RobotStockService(failingRepository, em);
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.execute(status -> failingService.move(
                source.getId(), new RobotStockMoveRequest(DEMO, 2), null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("robot_stock.changed_retry");
        var rows = robots.findModel("Concurrency test", model, "");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getQuantity()).isEqualTo(2);
        assertThat(rows.getFirst().getPreviousQuantity()).isNull();
    }

    private static void together(Runnable first, Runnable second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (Runnable task : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                    task.run();
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
    }
}
