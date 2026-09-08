package dev.sonti.inventory;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PostgresProcessIT extends PostgresFixture {
    @BeforeEach void reset() {
        sql.execute("TRUNCATE idempotency,reservation,stock,outbox CASCADE");
    }
    @Test void committedStateSurvivesProcessKillAndRestart() throws Exception {
        var request = new Reservations.Reserve("tenant", "sku", 2, 900, "same");
        String id;
        try (var first = new RunningApplication()) {
            first.request("POST", "/api/v1/stock", Map.of("tenantId","tenant","sku","sku","quantity",10), 200);
            id = first.request("POST", "/api/v1/reservations", request, 200).get("reservationId").asString();
            first.crash();
        }
        try (var restarted = new RunningApplication()) {
            assertThat(restarted.request("POST", "/api/v1/reservations", request, 200).get("reservationId").asString()).isEqualTo(id);
            restarted.request("POST", "/api/v1/reservations/tenant/" + id + "/confirm", null, 200);
            restarted.request("POST", "/api/v1/reservations/tenant/" + id + "/confirm", null, 200);
            var stock = restarted.request("GET", "/api/v1/stock/tenant/sku", null, 200);
            assertThat(stock.get("sold").asInt()).isEqualTo(2);
            assertThat(stock.get("available").asInt()).isEqualTo(8);
            assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(2);
        }
    }
    @Test void twoApiProcessesShareAuthoritativeStock() throws Exception {
        try (var first = new RunningApplication(); var second = new RunningApplication();
                var executor = java.util.concurrent.Executors.newFixedThreadPool(16)) {
            first.request("POST", "/api/v1/stock", Map.of("tenantId","tenant","sku","sku","quantity",10), 200);
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int i=0; i<40; i++) {
                String key = "k" + i;
                var app = i % 2 == 0 ? first : second;
                tasks.add(() -> {
                    // Each successful/failed request is checked using the documented conflict behavior.
                    try { app.request("POST", "/api/v1/reservations", new Reservations.Reserve("tenant","sku",1,900,key), 200); }
                    catch (AssertionError conflict) { assertThat(conflict.getMessage()).contains("got 409").contains("Insufficient stock."); }
                    return null;
                });
            }
            for (var result : executor.invokeAll(tasks)) result.get();
            assertThat(sql.queryForObject("SELECT count(*) FROM reservation", Integer.class)).isEqualTo(10);
            assertThat(sql.queryForObject("SELECT available_qty FROM stock", Integer.class)).isZero();
        }
    }
}
