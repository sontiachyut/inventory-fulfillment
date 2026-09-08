package dev.sonti.inventory;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@Configuration
@Profile("local-demo")
class DemoConfiguration {
    @Bean
    Reservations reservations(Clock clock) { return new Reservations(clock, 10_000); }
}

@RestController
@Profile({"local-demo", "postgres-local"})
@RequestMapping("/api/v1")
class ReservationApi {
    record CreateStock(String tenantId, String sku, int quantity) {
        CreateStock { Input.identifier(tenantId); Input.identifier(sku); Input.quantity(quantity); }
    }
    private final ReservationService reservations;
    ReservationApi(ReservationService reservations) { this.reservations = reservations; }

    @PostMapping("/stock")
    Reservations.Stock create(@RequestBody CreateStock request) {
        return reservations.createStock(request.tenantId(), request.sku(), request.quantity());
    }
    @GetMapping("/stock/{tenantId}/{sku}")
    Reservations.Stock stock(@PathVariable String tenantId, @PathVariable String sku) {
        return reservations.stock(tenantId, sku);
    }
    @PostMapping("/reservations")
    Reservations.Reservation reserve(@RequestBody Reservations.Reserve request) {
        return reservations.reserve(request);
    }
    @GetMapping("/reservations/{tenantId}/{id}")
    Reservations.Reservation get(@PathVariable String tenantId, @PathVariable UUID id) {
        return reservations.get(tenantId, id);
    }
    @PostMapping("/reservations/{tenantId}/{id}/confirm")
    Reservations.Reservation confirm(@PathVariable String tenantId, @PathVariable UUID id) {
        return reservations.confirm(tenantId, id);
    }
    @PostMapping("/reservations/{tenantId}/{id}/cancel")
    Reservations.Reservation cancel(@PathVariable String tenantId, @PathVariable UUID id) {
        return reservations.cancel(tenantId, id);
    }
    @PostMapping("/demo/expire")
    Map<String, Integer> expire() { return Map.of("expired", reservations.expire()); }
}
