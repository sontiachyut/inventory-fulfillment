package dev.sonti.inventory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=local-demo")
class ApiTest {
    @LocalServerPort int port;
    private final JsonMapper json = JsonMapper.builder().build();
    private record Response(int status, JsonNode body, String contentType) {}
    @Test void fractionalAndNullIntegersAreRejected() throws Exception {
        String base = """
                {"tenantId":"t","sku":"s","quantity":VALUE}
                """;
        for (String value : new String[] {"1.5", "null", "\"2\""}) {
            assertThat(send("POST", "/api/v1/stock", base.replace("VALUE", value)).status()).isEqualTo(400);
        }
    }
    private Response send(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), json.readTree(response.body()),
                    response.headers().firstValue("Content-Type").orElse(""));
        }
    }

    private String createTenant() throws Exception {
        String tenant = UUID.randomUUID().toString();
        assertThat(send("POST", "/api/v1/stock",
                "{\"tenantId\":\"" + tenant + "\",\"sku\":\"item\",\"quantity\":2}").status()).isEqualTo(200);
        return tenant;
    }
    private String reserve(String tenant, int quantity) {
        return """
                {"tenantId":"%s","sku":"item","quantity":%d,"ttlSeconds":60,"idempotencyKey":"same"}
                """.formatted(tenant, quantity);
    }
    @Test void reserveReplayAndConfirmRoundTrip() throws Exception {
        String tenant = createTenant();
        var first = send("POST", "/api/v1/reservations", reserve(tenant, 1));
        assertThat(first.status()).isEqualTo(200);
        String id = first.body().get("reservationId").asString();
        var replay = send("POST", "/api/v1/reservations", reserve(tenant, 1));
        assertThat(replay.body().get("reservationId").asString()).isEqualTo(id);
        var confirmed = send("POST", "/api/v1/reservations/" + tenant + "/" + id + "/confirm", null);
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.body().get("status").asString()).isEqualTo("CONFIRMED");
        var stock = send("GET", "/api/v1/stock/" + tenant + "/item", null);
        assertThat(stock.body().get("sold").asInt()).isEqualTo(1);
        assertThat(stock.body().get("available").asInt()).isEqualTo(1);
    }
    @Test void changedIdempotencyPayloadReturns409ProblemDetail() throws Exception {
        String tenant = createTenant();
        send("POST", "/api/v1/reservations", reserve(tenant, 1));
        var conflict = send("POST", "/api/v1/reservations", reserve(tenant, 2));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.contentType()).contains("application/problem+json");
    }
    @Test void otherTenantCannotReadOrCancelReservation() throws Exception {
        String tenant = createTenant();
        String id = send("POST", "/api/v1/reservations", reserve(tenant, 1)).body().get("reservationId").asString();
        assertThat(send("POST", "/api/v1/reservations/other/" + id + "/cancel", null).status()).isEqualTo(404);
        assertThat(send("GET", "/api/v1/reservations/other/" + id, null).status()).isEqualTo(404);
    }
    @Test void malformedInvalidAndMissingInputReturns400() throws Exception {
        assertThat(send("POST", "/api/v1/stock", "{bad").status()).isEqualTo(400);
        assertThat(send("POST", "/api/v1/stock", "{\"tenantId\":\"t\",\"sku\":\"s\"}").status()).isEqualTo(400);
        assertThat(send("POST", "/api/v1/reservations", reserve("tenant", -1)).status()).isEqualTo(400);
        assertThat(send("GET", "/api/v1/reservations/tenant/not-a-uuid", null).status()).isEqualTo(400);
    }
    @Test void overReservationFailsWithoutChangingStock() throws Exception {
        String tenant = createTenant();
        assertThat(send("POST", "/api/v1/reservations", reserve(tenant, 3)).status()).isEqualTo(409);
        assertThat(send("GET", "/api/v1/stock/" + tenant + "/item", null).body().get("available").asInt()).isEqualTo(2);
    }

}
