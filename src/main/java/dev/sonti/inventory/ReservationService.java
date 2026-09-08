package dev.sonti.inventory;

import java.util.UUID;

public interface ReservationService {
    Reservations.Stock createStock(String tenantId, String sku, int quantity);
    Reservations.Stock stock(String tenantId, String sku);
    Reservations.Reservation reserve(Reservations.Reserve intent);
    Reservations.Reservation get(String tenantId, UUID id);
    Reservations.Reservation confirm(String tenantId, UUID id);
    Reservations.Reservation cancel(String tenantId, UUID id);
    int expire();
}
