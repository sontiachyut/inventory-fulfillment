package dev.sonti.inventory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Packaged JVM test harness; owns and cleans up only its child process. */
final class RunningApplication implements AutoCloseable {
    private final Process process;
    private final StringBuffer output = new StringBuffer();
    private volatile int port;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    RunningApplication() throws Exception {
        var builder = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-jar",
                "target/inventory-fulfillment-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=postgres-local", "--server.port=0");
        builder.environment().put("APP_DATABASE_URL", PostgresFixture.postgres.getJdbcUrl());
        builder.environment().put("APP_DATABASE_USER", PostgresFixture.postgres.getUsername());
        builder.environment().put("APP_DATABASE_PASSWORD", PostgresFixture.postgres.getPassword());
        process = builder.redirectErrorStream(true).start();
        Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                for (String line; (line = reader.readLine()) != null;) {
                    synchronized (output) {
                        output.append(line).append('\n');
                        if (output.length() > 12000) output.delete(0, output.length() - 12000);
                    }
                    var match = Pattern.compile("Tomcat started on port (\\d+)").matcher(line);
                    if (match.find()) port = Integer.parseInt(match.group(1));
                }
            } catch (Exception ignored) { /* The process exit/output below determines test failure. */ }
        });
        try {
            awaitReady(process::isAlive, () -> {
                if (port == 0) return false;
                var probe = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                        .timeout(Duration.ofSeconds(1)).GET().build();
                try {
                    var response = client.send(probe, HttpResponse.BodyHandlers.ofString());
                    return response.statusCode() == 200 && "UP".equals(json.readTree(response.body()).path("status").asString());
                } catch (java.io.IOException notReady) { return false; }
            }, Duration.ofSeconds(30));
        } catch (Exception | AssertionError failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("Application startup failed: " + output, failure);
        }
    }
    static void awaitReady(java.util.function.BooleanSupplier alive, java.util.concurrent.Callable<Boolean> probe,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        // A listening port precedes Spring readiness; retry only the startup health probe, never business requests.
        while (alive.getAsBoolean() && System.nanoTime() < deadline) {
            if (probe.call()) return;
            Thread.sleep(20);
        }
        throw new IllegalStateException("Application did not become healthy before exit/deadline.");
    }
    JsonNode request(String method, String path, Object body, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                        HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != expected) throw new AssertionError("Expected " + expected + " got " + response.statusCode() + ": " + response.body());
        return json.readTree(response.body());
    }
    void crash() throws Exception {
        process.destroyForcibly();
        if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("Child did not terminate.");
    }
    @Override public void close() throws Exception {
        client.close();
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) crash();
        }
    }
}
