package com.example.platform.agent.tool;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Counts only Surefire XML reports written by this invocation. XML content is never logged. */
public final class SurefireReportReader {
    private static final int MAX_REPORTS = 100;
    private static final long MAX_REPORT_BYTES = 1_000_000;
    private static final int MAX_FAILURES = 5;

    public record Failure(String module, String test, String type, String message) {}
    record Summary(int total, int failed, int errors, int skipped, int serviceTests,
                   List<Failure> failures, boolean incomplete) {
        int passed() { return Math.max(0, total - failed - errors - skipped); }
    }

    static Summary read(Path root, String service, Instant startedAt) {
        var state = new State();
        inspect(state, root.resolve("services").resolve(service).resolve("target/surefire-reports"),
                service, true, startedAt);
        if (!service.equals("engineering-agent")) {
            inspect(state, root.resolve("libs/common-observability/target/surefire-reports"),
                    "common-observability", false, startedAt);
        }
        return new Summary(state.total, state.failed, state.errors, state.skipped, state.serviceTests,
                List.copyOf(state.failures), state.incomplete || state.failed + state.errors + state.skipped > state.total);
    }

    private static void inspect(State state, Path directory, String module, boolean selected, Instant startedAt) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            state.incomplete = true;
            return;
        }
        try (var paths = Files.list(directory)) {
            var candidates = paths.filter(path -> path.getFileName().toString().startsWith("TEST-")
                    && path.getFileName().toString().endsWith(".xml")).sorted().limit(1_001).toList();
            if (candidates.size() == 1_001) state.incomplete = true;
            for (Path report : candidates) {
                if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)) {
                    state.incomplete = true;
                    continue;
                }
                if (Files.getLastModifiedTime(report, LinkOption.NOFOLLOW_LINKS)
                        .compareTo(FileTime.from(startedAt)) < 0) continue;
                if (++state.reports > MAX_REPORTS || Files.size(report) > MAX_REPORT_BYTES) {
                    state.incomplete = true;
                    continue;
                }
                parse(state, report, module, selected);
            }
        } catch (IOException | SecurityException ex) {
            state.incomplete = true;
        }
    }

    private static void parse(State state, Path report, String module, boolean selected) {
        try (var input = Files.newInputStream(report, LinkOption.NOFOLLOW_LINKS)) {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            var xml = factory.createXMLStreamReader(input);
            try {
                int total = -1, failed = 0, errors = 0, skipped = 0;
                String test = "";
                var reportFailures = new ArrayList<Failure>();
                while (xml.hasNext()) {
                    int event = xml.next();
                    if (event == XMLStreamConstants.DTD) throw new XMLStreamException("DTD is not allowed");
                    if (event != XMLStreamConstants.START_ELEMENT) continue;
                    String element = xml.getLocalName();
                    if (element.equals("testsuite")) {
                        total = count(xml.getAttributeValue(null, "tests"));
                        failed = count(xml.getAttributeValue(null, "failures"));
                        errors = count(xml.getAttributeValue(null, "errors"));
                        skipped = count(xml.getAttributeValue(null, "skipped"));
                    } else if (element.equals("testcase")) {
                        test = safe(xml.getAttributeValue(null, "classname"), 100) + "."
                                + safe(xml.getAttributeValue(null, "name"), 100);
                    } else if ((element.equals("failure") || element.equals("error"))
                            && state.failures.size() + reportFailures.size() < MAX_FAILURES) {
                        reportFailures.add(new Failure(module, test, element.toUpperCase(),
                                safe(xml.getAttributeValue(null, "message"), 200)));
                    }
                }
                if (total < 0 || failed + errors + skipped > total) throw new XMLStreamException("Invalid test counts");
                state.total += total;
                state.failed += failed;
                state.errors += errors;
                state.skipped += skipped;
                if (selected) state.serviceTests += total;
                state.failures.addAll(reportFailures);
            } finally {
                xml.close();
            }
        } catch (IOException | XMLStreamException | IllegalArgumentException ex) {
            state.incomplete = true;
        }
    }

    private static int count(String value) {
        if (value == null) throw new IllegalArgumentException("Missing test count");
        int count = Integer.parseInt(value);
        if (count < 0 || count > 100_000) throw new IllegalArgumentException("Invalid test count");
        return count;
    }

    private static String safe(String value, int limit) {
        if (value == null) return "";
        String redacted = GitDiffReader.redact(value.replaceAll("[\\p{Cntrl}]", " "));
        return redacted.length() > limit ? redacted.substring(0, limit) + "…" : redacted;
    }

    private static final class State {
        int reports, total, failed, errors, skipped, serviceTests;
        boolean incomplete;
        final List<Failure> failures = new ArrayList<>();
    }
}
