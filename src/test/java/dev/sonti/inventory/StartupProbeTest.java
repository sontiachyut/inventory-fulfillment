package dev.sonti.inventory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StartupProbeTest {
    @Test void waitsForHealthRatherThanTreatingOpenedPortAsReady() throws Exception {
        var probes = new AtomicInteger();
        RunningApplication.awaitReady(() -> true, () -> probes.incrementAndGet() >= 3, Duration.ofSeconds(2));
        assertThat(probes.get()).isEqualTo(3);
    }
    @Test void unhealthyProcessFailsAtDeadline() {
        assertThatThrownBy(() -> RunningApplication.awaitReady(() -> true, () -> false, Duration.ofMillis(30)))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void exitedProcessFailsWithoutProbing() {
        var probes = new AtomicInteger();
        assertThatThrownBy(() -> RunningApplication.awaitReady(() -> false, () -> probes.incrementAndGet() > 0, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(probes.get()).isZero();
    }
}
