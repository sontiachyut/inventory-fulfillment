package dev.sonti.inventory;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Single-process, bounded reference model. No durability or multi-instance guarantees. */
public final class Reservations implements ReservationService {
    public enum Status { ACTIVE, CONFIRMED, CANCELLED, EXPIRED }
    private record StockKey(String tenantId, String sku) {
        StockKey { Input.identifier(tenantId); Input.identifier(sku); }
    }
    private record RequestKey(String tenantId, String idempotencyKey) {}
    public record Stock(String tenantId, String sku, int initial, int available, int reserved, int sold) {}
    public record Reservation(UUID reservationId, String tenantId, String sku, int quantity,
                              Instant expiresAt, Status status) {}
    public record Reserve(String tenantId, String sku, int quantity, int ttlSeconds, String idempotencyKey) {
        public Reserve {
            new StockKey(tenantId, sku);
            Input.identifier(idempotencyKey);
            Input.quantity(quantity);
            if (ttlSeconds < 1 || ttlSeconds > 900) throw new IllegalArgumentException("TTL must be 1-900 seconds.");
        }
    }
    private record Request(Reserve intent, UUID reservationId) {}

    private final Clock clock;
    private final int reservationCapacity;
    private final Map<StockKey, Stock> stocks = new HashMap<>();
    private final Map<UUID, Reservation> reservations = new HashMap<>();
    private final Map<RequestKey, Request> requests = new HashMap<>();

    public Reservations(Clock clock, int reservationCapacity) {
        if (clock == null || reservationCapacity < 1) throw new IllegalArgumentException("Invalid configuration.");
        this.clock = clock;
        this.reservationCapacity = reservationCapacity;
    }

    public synchronized Stock createStock(String tenantId, String sku, int quantity) {
        StockKey key = new StockKey(tenantId, sku);
        Input.quantity(quantity);
        if (stocks.containsKey(key)) throw new DomainException(409, "Stock already exists.");
        if (stocks.size() >= 1_000) throw new DomainException(503, "Local demo stock capacity reached.");
        Stock stock = new Stock(tenantId, sku, quantity, quantity, 0, 0);
        stocks.put(key, stock);
        return stock;
    }

    public synchronized Stock stock(String tenantId, String sku) {
        expire();
        Stock stock = stocks.get(new StockKey(tenantId, sku));
        if (stock == null) throw new DomainException(404, "Stock not found.");
        return stock;
    }

    public synchronized Reservation reserve(Reserve intent) {
        expire();
        RequestKey key = new RequestKey(intent.tenantId(), intent.idempotencyKey());
        Request prior = requests.get(key);
        if (prior != null) {
            if (!prior.intent().equals(intent)) throw new DomainException(409, "Idempotency key has different intent.");
            return reservations.get(prior.reservationId());
        }
        if (reservations.size() >= reservationCapacity) throw new DomainException(503, "Local demo reservation capacity reached.");
        StockKey stockKey = new StockKey(intent.tenantId(), intent.sku());
        Stock stock = stock(intent.tenantId(), intent.sku());
        if (stock.available() < intent.quantity()) throw new DomainException(409, "Insufficient stock.");
        Instant expiresAt = clock.instant().plusSeconds(intent.ttlSeconds());
        Reservation reservation = new Reservation(UUID.randomUUID(), intent.tenantId(), intent.sku(),
                intent.quantity(), expiresAt, Status.ACTIVE);
        stocks.put(stockKey, new Stock(stock.tenantId(), stock.sku(), stock.initial(),
                stock.available() - intent.quantity(), stock.reserved() + intent.quantity(), stock.sold()));
        reservations.put(reservation.reservationId(), reservation);
        requests.put(key, new Request(intent, reservation.reservationId()));
        return reservation;
    }

    public synchronized Reservation get(String tenantId, UUID id) {
        Input.identifier(tenantId);
        expire();
        Reservation reservation = reservations.get(id);
        if (reservation == null || !reservation.tenantId().equals(tenantId)) {
            throw new DomainException(404, "Reservation not found.");
        }
        return reservation;
    }

    public synchronized Reservation confirm(String tenantId, UUID id) {
        return command(tenantId, id, Status.CONFIRMED);
    }

    public synchronized Reservation cancel(String tenantId, UUID id) {
        return command(tenantId, id, Status.CANCELLED);
    }

    private Reservation command(String tenantId, UUID id, Status target) {
        Reservation reservation = get(tenantId, id);
        if (reservation.status() == target) return reservation;
        if (reservation.status() != Status.ACTIVE) throw new DomainException(409, "Reservation is " + reservation.status() + ".");
        return transition(reservation, target);
    }

    public synchronized int expire() {
        Instant now = clock.instant();
        int expired = 0;
        // Replacing existing values does not structurally modify the map.
        for (Reservation reservation : reservations.values()) {
            if (reservation.status() == Status.ACTIVE && !now.isBefore(reservation.expiresAt())) {
                transition(reservation, Status.EXPIRED);
                expired++;
            }
        }
        return expired;
    }

    private Reservation transition(Reservation reservation, Status target) {
        StockKey key = new StockKey(reservation.tenantId(), reservation.sku());
        Stock stock = stocks.get(key);
        int quantity = reservation.quantity();
        boolean sale = target == Status.CONFIRMED;
        stocks.put(key, new Stock(stock.tenantId(), stock.sku(), stock.initial(),
                stock.available() + (sale ? 0 : quantity),
                stock.reserved() - quantity, stock.sold() + (sale ? quantity : 0)));
        Reservation changed = new Reservation(reservation.reservationId(), reservation.tenantId(), reservation.sku(),
                quantity, reservation.expiresAt(), target);
        reservations.put(changed.reservationId(), changed);
        return changed;
    }
}
