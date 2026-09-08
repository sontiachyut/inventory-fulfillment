package dev.sonti.inventory;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PostgresReservationsIT extends PostgresFixture {
    private PostgresReservations engine;
    @BeforeEach void reset() {
        sql.execute("TRUNCATE idempotency, reservation, stock, outbox CASCADE");
        engine = new PostgresReservations(sql, transactions, JsonMapper.builder().build());
        engine.createStock("tenant", "sku", 100);
    }
    private Reservations.Reserve intent(String key, int quantity) {
        return new Reservations.Reserve("tenant", "sku", quantity, 60, key);
    }
    private void conserved() {
        var s = engine.stock("tenant", "sku");
        assertThat(s.available()).isNotNegative();
        assertThat(s.reserved()).isNotNegative();
        assertThat(s.sold()).isNotNegative();
        assertThat(s.available() + s.reserved() + s.sold()).isEqualTo(s.initial());
    }
    @Test void reserveReplayAndConfirmWriteOneEventPerTransition() {
        var first = engine.reserve(intent("key", 2));
        assertThat(engine.reserve(intent("key", 2))).isEqualTo(first);
        engine.confirm("tenant", first.reservationId());
        engine.confirm("tenant", first.reservationId());
        assertThat(engine.stock("tenant", "sku").sold()).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT count(*) FROM idempotency", Integer.class)).isEqualTo(1);
        conserved();
    }
    @Test void changedIntentConflictsAndFailedRequestsDoNotClaimKeys() {
        engine.reserve(intent("key", 2));
        assertThatThrownBy(() -> engine.reserve(intent("key", 3))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> engine.reserve(intent("too-much", 101))).isInstanceOf(DomainException.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM idempotency", Integer.class)).isEqualTo(1);
        conserved();
    }
    @Test void thousandAttemptsAcrossTwoConnectionPoolsAcceptExactlyHundred() throws Exception {
        try (var otherPool = newPool(); var executor = Executors.newFixedThreadPool(32)) {
            var other = new PostgresReservations(new JdbcTemplate(otherPool),
                    new TransactionTemplate(new JdbcTransactionManager(otherPool)), JsonMapper.builder().build());
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 1000; i++) {
                String key = "key" + i;
                var target = i % 2 == 0 ? engine : other;
                tasks.add(() -> { try { target.reserve(intent(key, 1)); return true; }
                    catch (DomainException rejected) { assertThat(rejected.getMessage()).isEqualTo("Insufficient stock."); return false; } });
            }
            int accepted = 0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(100);
            assertThat(other.stock("tenant", "sku").available()).isZero();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(100);
        conserved();
    }
    @Test void concurrentDuplicateKeysAllocateOnce() throws Exception {
        try (var executor = Executors.newFixedThreadPool(16)) {
            var tasks = new ArrayList<Callable<Reservations.Reservation>>();
            for (int i = 0; i < 100; i++) tasks.add(() -> engine.reserve(intent("same", 1)));
            var ids = new java.util.HashSet<java.util.UUID>();
            for (var result : executor.invokeAll(tasks)) ids.add(result.get().reservationId());
            assertThat(ids).hasSize(1);
        }
        assertThat(engine.stock("tenant", "sku").reserved()).isEqualTo(1);
    }
    @Test void outboxInsertFailureRollsBackAllocationAndRequestClaim() {
        sql.execute("ALTER TABLE outbox ADD CONSTRAINT test_reject CHECK (event_type='impossible') NOT VALID");
        try {
            assertThatThrownBy(() -> engine.reserve(intent("key", 2))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(engine.stock("tenant", "sku").available()).isEqualTo(100);
            assertThat(sql.queryForObject("SELECT count(*) FROM reservation", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM idempotency", Integer.class)).isZero();
        } finally { sql.execute("ALTER TABLE outbox DROP CONSTRAINT test_reject"); }
    }
    @Test void lateConfirmationCommitsExpiryEvenThoughCommandReturnsConflict() {
        var r = engine.reserve(intent("key", 3));
        sql.update("UPDATE reservation SET expires_at=clock_timestamp() - interval '1 second' WHERE id=?", r.reservationId());
        assertThatThrownBy(() -> engine.confirm("tenant", r.reservationId())).isInstanceOf(DomainException.class);
        assertThat(engine.get("tenant", r.reservationId()).status()).isEqualTo(Reservations.Status.EXPIRED);
        assertThat(engine.stock("tenant", "sku").available()).isEqualTo(100);
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(2);
        assertThat(engine.reserve(intent("key", 3)).status()).isEqualTo(Reservations.Status.EXPIRED);
        conserved();
    }
    @Test void competingSweepersReleaseEachReservationOnlyOnce() throws Exception {
        for (int i=0; i<50; i++) engine.reserve(intent("k"+i, 1));
        sql.execute("UPDATE reservation SET expires_at=clock_timestamp() - interval '1 second'");
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var result : executor.invokeAll(java.util.List.<Callable<Integer>>of(engine::expire, engine::expire, engine::expire, engine::expire))) result.get();
        }
        assertThat(engine.stock("tenant", "sku").available()).isEqualTo(100);
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(100);
        conserved();
    }
    @Test void confirmCancelRaceHasOneTerminalWinner() throws Exception {
        var r = engine.reserve(intent("key", 3));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.List.<Callable<Void>>of(
                () -> { try { engine.confirm("tenant", r.reservationId()); } catch (DomainException e) { assertThat(e.status()).isEqualTo(409); } return null; },
                () -> { try { engine.cancel("tenant", r.reservationId()); } catch (DomainException e) { assertThat(e.status()).isEqualTo(409); } return null; });
            for (var result : executor.invokeAll(tasks)) result.get();
        }
        assertThat(engine.get("tenant", r.reservationId()).status()).isIn(Reservations.Status.CONFIRMED, Reservations.Status.CANCELLED);
        assertThat(engine.stock("tenant", "sku").reserved()).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(2);
        conserved();
    }
    @Test void tenantIsolationAndDatabaseConstraints() {
        var r = engine.reserve(intent("key", 1));
        assertThatThrownBy(() -> engine.cancel("other", r.reservationId()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(() -> sql.update("UPDATE stock SET available_qty=-1")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> sql.update("UPDATE stock SET available_qty=98")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        conserved();
    }
    @Test void confirmationSamplesDeadlineAfterWaitingForStockLock() throws Exception {
        var r = engine.reserve(new Reservations.Reserve("tenant", "sku", 1, 2, "blocked"));
        try (var executor = Executors.newSingleThreadExecutor(); var blocker = pool.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                try (var query = blocker.prepareStatement("SELECT sku FROM stock WHERE tenant_id='tenant' AND sku='sku' FOR UPDATE")) {
                    query.executeQuery().close();
                }
                var result = executor.submit(() -> engine.confirm("tenant", r.reservationId()));
                long timeout = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
                while (sql.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT sku FROM stock%'", Integer.class) == 0) {
                    assertThat(System.nanoTime()).isLessThan(timeout);
                    Thread.sleep(10);
                }
                while (sql.queryForObject("SELECT clock_timestamp() < ?", Boolean.class, java.sql.Timestamp.from(r.expiresAt()))) {
                    assertThat(System.nanoTime()).isLessThan(timeout);
                    Thread.sleep(10);
                }
                blocker.commit();
                assertThatThrownBy(() -> result.get(5, java.util.concurrent.TimeUnit.SECONDS))
                        .hasCauseInstanceOf(DomainException.class);
                assertThat(engine.stock("tenant", "sku").sold()).isZero();
                assertThat(engine.get("tenant", r.reservationId()).status()).isEqualTo(Reservations.Status.EXPIRED);
            } finally { blocker.rollback(); }
        }
        conserved();
    }
    @Test void outboxFailureDuringConfirmationRollsBackStateAndBalances() {
        var r = engine.reserve(intent("key", 2));
        sql.execute("ALTER TABLE outbox ADD CONSTRAINT test_reject CHECK (event_type='impossible') NOT VALID");
        try {
            assertThatThrownBy(() -> engine.confirm("tenant", r.reservationId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(engine.get("tenant", r.reservationId()).status()).isEqualTo(Reservations.Status.ACTIVE);
            assertThat(engine.stock("tenant", "sku").reserved()).isEqualTo(2);
            assertThat(engine.stock("tenant", "sku").sold()).isZero();
        } finally { sql.execute("ALTER TABLE outbox DROP CONSTRAINT test_reject"); }
        conserved();
    }
}
