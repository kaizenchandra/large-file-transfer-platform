package com.example.drive;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Local-only bypass must not make development health depend on an unused daemon.
 */
@Component("contentScanner")
public class ScanHealth implements HealthIndicator {
    private final MalwareGate gate;
    private final ClamAvScanner scanner;

    public ScanHealth(MalwareGate gate, ClamAvScanner scanner) {
        this.gate = gate;
        this.scanner = scanner;
    }

    @Override
    public Health health() {
        return gate.required() ? scanner.health() : Health.up().withDetail("mode", "local-bypass").build();
    }
}
