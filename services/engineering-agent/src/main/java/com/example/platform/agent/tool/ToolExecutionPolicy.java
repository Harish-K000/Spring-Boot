package com.example.platform.agent.tool;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Source-path allowlist. Filesystem checks are additionally enforced by the reader. */
public final class ToolExecutionPolicy {
    private static final Set<String> SERVICES = Set.of("auth-service", "orders-service", "inventory-service",
            "payments-service", "edge-gateway", "worker", "engineering-agent");
    private ToolExecutionPolicy() {}

    static Path sourcePath(String input) {
        if (input == null || input.isBlank() || input.length() > 1024
                || input.contains("\\") || input.contains(":") || input.contains("%")
                || input.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Use a plain repository-relative source path.");
        }
        Path path = Path.of(input);
        if (path.isAbsolute()) throw new IllegalArgumentException("Absolute paths are not allowed.");
        for (String segment : input.split("/", -1)) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (segment.isEmpty() || segment.startsWith(".") || lower.equals("target")
                    || lower.equals("node_modules") || lower.contains("secret")
                    || lower.contains("credential")) {
                throw new IllegalArgumentException("Hidden, generated, sensitive and traversal paths are not allowed.");
            }
        }
        String name = path.getFileName().toString();
        if (!(name.endsWith(".java") || name.endsWith(".sql") || name.equals("pom.xml"))) {
            throw new IllegalArgumentException("Only Java, SQL and pom.xml files are allowed.");
        }
        return path;
    }

    static Set<String> approvedServices() {
        return SERVICES;
    }

    public static String serviceName(String input) {
        if (input == null || !SERVICES.contains(input)) {
            throw new IllegalArgumentException("Only registered services may be built.");
        }
        return input;
    }
}
