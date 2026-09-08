package dev.sonti.inventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReservationsTest {
    private final MutableClock clock = new MutableClock();
    private final Reservations engine = new Reservations(clock, 10_000);
    @BeforeEach void seed() { engine.createStock("tenant", "sku", 100); }
    private Reservations.Reserve request(String key, int quantity) {
        return new Reservations.Reserve("tenant", "sku", quantity, 60, key);
    }
    private void conserved() {
        var s = engine.stock("tenant", "sku");
        assertThat(s.available()).isNotNegative();
        assertThat(s.reserved()).isNotNegative();
        assertThat(s.sold()).isNotNegative();
        assertThat(s.available() + s.reserved() + s.sold()).isEqualTo(s.initial());
    }

    @Test void reserveMovesAvailableToReserved() {
        engine.reserve(request("r1", 3));
        assertThat(engine.stock("tenant", "sku").available()).isEqualTo(97);
        assertThat(engine.stock("tenant", "sku").reserved()).isEqualTo(3);
        conserved();
    }
    @Test void replayReturnsSameReservationWithoutAllocation() {
        var first = engine.reserve(request("r1", 3));
        assertThat(engine.reserve(request("r1", 3))).isEqualTo(first);
        assertThat(engine.stock("tenant", "sku").reserved()).isEqualTo(3);
    }
    @Test void changedPayloadForSameKeyConflicts() {
        engine.reserve(request("r1", 3));
        assertThatThrownBy(() -> engine.reserve(request("r1", 4))).isInstanceOf(DomainException.class);
        conserved();
    }
    @Test void changedTtlForSameKeyConflicts() {
        engine.reserve(request("r1", 3));
        assertThatThrownBy(() -> engine.reserve(new Reservations.Reserve("tenant", "sku", 3, 61, "r1")))
                .isInstanceOf(DomainException.class);
    }
    @Test void insufficientStockLeavesBalancesUnchanged() {
        assertThatThrownBy(() -> engine.reserve(request("r1", 101))).isInstanceOf(DomainException.class);
        assertThat(engine.stock("tenant", "sku").available()).isEqualTo(100);
    }
    @Test void confirmAndRetrySellOnlyOnce() {
        var r = engine.reserve(request("r1", 3));
        engine.confirm("tenant", r.reservationId());
        engine.confirm("tenant", r.reservationId());
        assertThat(engine.stock("tenant", "sku").sold()).isEqualTo(3);
        assertThat(engine.stock("tenant", "sku").reserved()).isZero();
        conserved();
    }
    @Test void cancelAndRetryReleaseOnlyOnce() {
        var r = engine.reserve(request("r1", 3));
        engine.cancel("tenant", r.reservationId());
        engine.cancel("tenant", r.reservationId());
        assertThat(engine.stock("tenant", "sku").available()).isEqualTo(100);
        conserved();
    }
    @Test void cancelledCannotConfirm() {
        var r = engine.reserve(request("r1", 3));
        engine.cancel("tenant", r.reservationId());
        assertThatThrownBy(() -> engine.confirm("tenant", r.reservationId())).isInstanceOf(DomainException.class);
    }
    @Test void confirmedCannotCancelOrExpire() {
        var r = engine.reserve(request("r1", 3));
        engine.confirm("tenant", r.reservationId());
        assertThatThrownBy(() -> engine.cancel("tenant", r.reservationId())).isInstanceOf(DomainException.class);
        clock.advance(Duration.ofMinutes(2));
        assertThat(engine.expire()).isZero();
        assertThat(engine.get("tenant", r.reservationId()).status()).isEqualTo(Reservations.Status.CONFIRMED);
    }
    @Test void exactExpiryBoundaryRejectsLateConfirmationAndReleasesStock() {
        var r = engine.reserve(request("r1", 3));
        clock.advance(Duration.ofSeconds(60));
        assertThatThrownBy(() -> engine.confirm("tenant", r.reservationId())).isInstanceOf(DomainException.class);
        assertThat(engine.get("tenant", r.reservationId()).status()).isEqualTo(Reservations.Status.EXPIRED);
        assertThat(engine.stock("tenant", "sku").available()).isEqualTo(100);
        assertThat(engine.expire()).isZero();
    }
    @Test void replayDoesNotExtendExpiredReservation() {
        var r = engine.reserve(request("r1", 3));
        clock.advance(Duration.ofSeconds(60));
        var replay = engine.reserve(request("r1", 3));
        assertThat(replay.reservationId()).isEqualTo(r.reservationId());
        assertThat(replay.expiresAt()).isEqualTo(r.expiresAt());
        assertThat(replay.status()).isEqualTo(Reservations.Status.EXPIRED);
    }
    @Test void tenantCannotReadOrMutateAnotherReservation() {
        var r = engine.reserve(request("r1", 3));
        assertThatThrownBy(() -> engine.confirm("other", r.reservationId()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThat(engine.get("tenant", r.reservationId()).status()).isEqualTo(Reservations.Status.ACTIVE);
    }
    @Test void sameIdempotencyKeyInDifferentTenantsIsIndependent() {
        engine.createStock("other", "sku", 10);
        var r = engine.reserve(request("r1", 3));
        var other = engine.reserve(new Reservations.Reserve("other", "sku", 3, 60, "r1"));
        assertThat(r.reservationId()).isNotEqualTo(other.reservationId());
    }
    @Test void invalidQuantitiesAndTtlRejected() {
        assertThatThrownBy(() -> request("r", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("r", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Reservations.Reserve("tenant", "sku", 1, 0, "r"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Reservations.Reserve("tenant", "sku", 1, 901, "r"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void stockCannotBeResetByRepeatedSeed() {
        engine.reserve(request("r", 3));
        assertThatThrownBy(() -> engine.createStock("tenant", "sku", 100)).isInstanceOf(DomainException.class);
        assertThat(engine.stock("tenant", "sku").available()).isEqualTo(97);
    }
    @Test void boundedHistoryDoesNotEvictIdempotency() {
        var bounded = new Reservations(clock, 1);
        bounded.createStock("tenant", "sku", 10);
        var first = bounded.reserve(request("r1", 1));
        assertThatThrownBy(() -> bounded.reserve(request("r2", 1)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.status()).isEqualTo(503));
        assertThat(bounded.reserve(request("r1", 1))).isEqualTo(first);
    }
    @Test void thousandThreadsTasksCompeteForOneHundredUnitsInSingleProcess() throws Exception {
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 1_000; i++) {
            String key = "r" + i;
            tasks.add(() -> {
                try { engine.reserve(request(key, 1)); return true; }
                catch (DomainException error) { assertThat(error.getMessage()).isEqualTo("Insufficient stock."); return false; }
            });
        }
        int accepted = 0;
        try (var executor = Executors.newFixedThreadPool(16)) {
            for (var future : executor.invokeAll(tasks)) if (future.get()) accepted++;
        }
        assertThat(accepted).isEqualTo(100);
        assertThat(engine.stock("tenant", "sku").available()).isZero();
        conserved();
    }
    @Test void concurrentDuplicateRequestsAllocateOnce() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<Reservations.Reservation>>();
            for (int i = 0; i < 100; i++) tasks.add(() -> engine.reserve(request("same", 1)));
            var ids = new java.util.HashSet<java.util.UUID>();
            for (var future : executor.invokeAll(tasks)) ids.add(future.get().reservationId());
            assertThat(ids).hasSize(1);
        }
        assertThat(engine.stock("tenant", "sku").reserved()).isEqualTo(1);
    }
    @Test void racingConfirmAndCancelHaveOneWinningTerminalState() throws Exception {
        var r = engine.reserve(request("race", 3));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Void> confirm = () -> { try { engine.confirm("tenant", r.reservationId()); } catch (DomainException e) { assertThat(e.status()).isEqualTo(409); } return null; };
            Callable<Void> cancel = () -> { try { engine.cancel("tenant", r.reservationId()); } catch (DomainException e) { assertThat(e.status()).isEqualTo(409); } return null; };
            for (var future : executor.invokeAll(java.util.List.of(confirm, cancel))) future.get();
        }
        assertThat(engine.get("tenant", r.reservationId()).status()).isIn(Reservations.Status.CONFIRMED, Reservations.Status.CANCELLED);
        assertThat(engine.stock("tenant", "sku").reserved()).isZero();
        conserved();
    }
    @Test void seededRandomOperationSequencePreservesStock() {
        var random = new Random(42);
        var ids = new ArrayList<java.util.UUID>();
        for (int i = 0; i < 1_000; i++) {
            try {
                switch (random.nextInt(4)) {
                    case 0 -> ids.add(engine.reserve(request("random" + i, 1 + random.nextInt(5))).reservationId());
                    case 1 -> { if (!ids.isEmpty()) engine.confirm("tenant", ids.get(random.nextInt(ids.size()))); }
                    case 2 -> { if (!ids.isEmpty()) engine.cancel("tenant", ids.get(random.nextInt(ids.size()))); }
                    default -> { clock.advance(Duration.ofSeconds(10)); engine.expire(); }
                }
            } catch (DomainException expectedConflict) { assertThat(expectedConflict.status()).isEqualTo(409); }
            conserved();
        }
    }
}
