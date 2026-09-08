package dev.sonti.inventory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static dev.sonti.inventory.Reservations.*;

/** Correctness comes from PostgreSQL transactions/constraints, not process-local locks. */
public final class PostgresReservations implements ReservationService {
    private record Versioned(Reservation value, long version) {}
    private record Prior(String hash, UUID id) {}
    private final JdbcTemplate sql;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public PostgresReservations(JdbcTemplate sql, TransactionTemplate tx, JsonMapper json) {
        this.sql = sql;
        this.tx = tx;
        this.json = json;
    }

    @Override public Stock createStock(String tenantId, String sku, int quantity) {
        Input.identifier(tenantId); Input.identifier(sku); Input.quantity(quantity);
        int inserted = sql.update("""
                INSERT INTO stock(tenant_id,sku,initial_qty,available_qty,reserved_qty,sold_qty)
                VALUES (?,?,?,?,0,0) ON CONFLICT DO NOTHING
                """, tenantId, sku, quantity, quantity);
        if (inserted == 0) throw new DomainException(409, "Stock already exists.");
        return stock(tenantId, sku);
    }

    @Override public Stock stock(String tenantId, String sku) {
        Input.identifier(tenantId); Input.identifier(sku);
        var stocks = sql.query("SELECT * FROM stock WHERE tenant_id=? AND sku=?",
                (rs, row) -> new Stock(tenantId, sku, rs.getInt("initial_qty"), rs.getInt("available_qty"),
                        rs.getInt("reserved_qty"), rs.getInt("sold_qty")), tenantId, sku);
        if (stocks.isEmpty()) throw new DomainException(404, "Stock not found.");
        return stocks.getFirst();
    }

    @Override public Reservation reserve(Reserve intent) {
        String hash = hash(json.writeValueAsString(List.of(intent.tenantId(), intent.sku(), intent.quantity(), intent.ttlSeconds())));
        return tx.execute(status -> {
            UUID id = UUID.randomUUID();
            int claimed = sql.update("""
                    INSERT INTO idempotency(tenant_id,operation,request_key,request_hash,reservation_id)
                    VALUES (?,'reserve',?,?,?) ON CONFLICT DO NOTHING
                    """, intent.tenantId(), intent.idempotencyKey(), hash, id);
            if (claimed == 0) {
                Prior prior = sql.queryForObject("""
                        SELECT request_hash,reservation_id FROM idempotency
                        WHERE tenant_id=? AND operation='reserve' AND request_key=?
                        """, (rs, row) -> new Prior(rs.getString(1), rs.getObject(2, UUID.class)),
                        intent.tenantId(), intent.idempotencyKey());
                if (!prior.hash().equals(hash)) throw new DomainException(409, "Idempotency key has different intent.");
                return resolve(intent.tenantId(), prior.id(), null, false);
            }
            int allocated = sql.update("""
                    UPDATE stock SET available_qty=available_qty-?,reserved_qty=reserved_qty+?,version=version+1
                    WHERE tenant_id=? AND sku=? AND available_qty>=?
                    """, intent.quantity(), intent.quantity(), intent.tenantId(), intent.sku(), intent.quantity());
            if (allocated == 0) {
                stock(intent.tenantId(), intent.sku()); // Distinguish unknown stock from insufficient stock.
                throw new DomainException(409, "Insufficient stock.");
            }
            Instant deadline = databaseTime().plusSeconds(intent.ttlSeconds());
            sql.update("""
                    INSERT INTO reservation(id,tenant_id,sku,quantity,state,version,expires_at)
                    VALUES (?,?,?,?,'ACTIVE',1,?)
                    """, id, intent.tenantId(), intent.sku(), intent.quantity(), Timestamp.from(deadline));
            Reservation result = new Reservation(id, intent.tenantId(), intent.sku(), intent.quantity(), deadline, Status.ACTIVE);
            event(result, 1);
            return result;
        });
    }

    @Override public Reservation get(String tenantId, UUID id) {
        Input.identifier(tenantId);
        return tx.execute(status -> resolve(tenantId, id, null, false));
    }

    @Override public Reservation confirm(String tenantId, UUID id) { return command(tenantId, id, Status.CONFIRMED); }
    @Override public Reservation cancel(String tenantId, UUID id) { return command(tenantId, id, Status.CANCELLED); }

    private Reservation command(String tenantId, UUID id, Status target) {
        Input.identifier(tenantId);
        Reservation result = tx.execute(status -> resolve(tenantId, id, target, false));
        // Expiry release and outbox must commit even when the requested transition cannot succeed.
        if (result.status() != target) throw new DomainException(409, "Reservation is " + result.status() + ".");
        return result;
    }

    private Reservation resolve(String tenantId, UUID id, Status target, boolean skipLocked) {
        var rows = sql.query("SELECT * FROM reservation WHERE tenant_id=? AND id=? FOR UPDATE" + (skipLocked ? " SKIP LOCKED" : ""),
                (rs, row) -> read(rs), tenantId, id);
        if (rows.isEmpty()) {
            if (skipLocked) return null;
            throw new DomainException(404, "Reservation not found.");
        }
        Versioned current = rows.getFirst();
        Reservation r = current.value();
        if (r.status() != Status.ACTIVE) return r;
        // Same lock order everywhere: reservation -> stock -> clock sample.
        sql.queryForObject("SELECT sku FROM stock WHERE tenant_id=? AND sku=? FOR UPDATE",
                String.class, tenantId, r.sku());
        Instant now = databaseTime();
        Status next = !now.isBefore(r.expiresAt()) ? Status.EXPIRED : target;
        if (next == null) return r;
        int available = next == Status.CONFIRMED ? 0 : r.quantity();
        int sold = next == Status.CONFIRMED ? r.quantity() : 0;
        sql.update("""
                UPDATE stock SET available_qty=available_qty+?,reserved_qty=reserved_qty-?,sold_qty=sold_qty+?,version=version+1
                WHERE tenant_id=? AND sku=?
                """, available, r.quantity(), sold, tenantId, r.sku());
        sql.update("UPDATE reservation SET state=?,version=version+1 WHERE id=?", next.name(), id);
        Reservation changed = new Reservation(id, tenantId, r.sku(), r.quantity(), r.expiresAt(), next);
        event(changed, current.version() + 1);
        return changed;
    }

    @Override public int expire() {
        var candidates = sql.query("""
                SELECT * FROM reservation WHERE state='ACTIVE' AND expires_at<=clock_timestamp()
                ORDER BY expires_at,id LIMIT 100
                """, (rs, row) -> read(rs).value());
        int count = 0;
        for (Reservation candidate : candidates) {
            Boolean changed = tx.execute(status -> {
                // Claim only still-active candidates. Other sweepers skip held reservation rows.
                var claimed = sql.query("SELECT id FROM reservation WHERE id=? AND state='ACTIVE' FOR UPDATE SKIP LOCKED",
                        (rs, row) -> rs.getObject(1, UUID.class), candidate.reservationId());
                if (claimed.isEmpty()) return false;
                Reservation result = resolve(candidate.tenantId(), candidate.reservationId(), null, true);
                return result != null && result.status() == Status.EXPIRED;
            });
            if (Boolean.TRUE.equals(changed)) count++;
        }
        return count;
    }

    private Versioned read(ResultSet rs) throws SQLException {
        return new Versioned(new Reservation(rs.getObject("id", UUID.class), rs.getString("tenant_id"), rs.getString("sku"),
                rs.getInt("quantity"), rs.getTimestamp("expires_at").toInstant(), Status.valueOf(rs.getString("state"))),
                rs.getLong("version"));
    }

    private Instant databaseTime() { return sql.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant(); }

    private void event(Reservation r, long version) {
        UUID eventId = UUID.randomUUID();
        String type = "Reservation" + switch (r.status()) {
            case ACTIVE -> "Created";
            case CONFIRMED -> "Confirmed";
            case CANCELLED -> "Cancelled";
            case EXPIRED -> "Expired";
        };
        String envelope = json.writeValueAsString(Map.of("eventId", eventId, "eventType", type, "schemaVersion", 1,
                "tenantId", r.tenantId(), "aggregateId", r.reservationId(), "aggregateVersion", version,
                "occurredAt", databaseTime(), "correlationId", eventId, "payload", r));
        sql.update("INSERT INTO outbox(event_id,event_type,tenant_id,aggregate_id,aggregate_version,payload) VALUES (?,?,?,?,?,?::jsonb)",
                eventId, type, r.tenantId(), r.reservationId(), version, envelope);
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
