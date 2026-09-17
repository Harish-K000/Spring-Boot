package com.example.platform.agent.service;

import com.example.platform.agent.dto.ReviewReport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Scores accepted, line-linked findings against fixture labels. No model prose is retained. */
final class EvaluationScorer {
    record FindingLabel(String category, int line) {}
    record Observation(EvaluationCases.Case fixture, String analysisStatus, int rejectedModelFindings,
                       List<ReviewReport.Finding> findings) {}
    record CaseScore(String id, String expectedCategory, List<Integer> expectedLines,
                     String analysisStatus, int rejectedModelFindings,
                     List<FindingLabel> acceptedFindings,
                     int truePositives, int falsePositives, int falseNegatives) {}
    record Metrics(int cases, int positiveCases, int cleanCases, int available,
                   int malformed, int unavailable, int rejectedModelFindings,
                   int truePositives, int falsePositives,
                   int falseNegatives, Double precision, Double recall) {}
    record Report(String model, Instant measuredAt, Metrics metrics, List<CaseScore> cases) {}

    static Report score(String model, List<Observation> observations) {
        var scores = new ArrayList<CaseScore>();
        int positive = 0, clean = 0, available = 0, malformed = 0, unavailable = 0;
        int rejected = 0, truePositives = 0, falsePositives = 0, falseNegatives = 0;
        for (Observation observation : observations) {
            var fixture = observation.fixture();
            if (fixture.positive()) positive++; else clean++;
            switch (observation.analysisStatus()) {
                case "AVAILABLE" -> available++;
                case "MALFORMED" -> malformed++;
                case "UNAVAILABLE" -> unavailable++;
                default -> { }
            }
            rejected += observation.rejectedModelFindings();
            var accepted = new ArrayList<FindingLabel>();
            int matched = 0, extras = 0;
            for (var finding : observation.findings()) {
                accepted.add(new FindingLabel(finding.category(), finding.line()));
                if (matched == 0 && fixture.positive()
                        && fixture.category().equals(finding.category())
                        && fixture.lines().contains(finding.line())) {
                    matched = 1;
                } else {
                    extras++;
                }
            }
            int missed = fixture.positive() && matched == 0 ? 1 : 0;
            truePositives += matched;
            falsePositives += extras;
            falseNegatives += missed;
            scores.add(new CaseScore(fixture.id(), fixture.category(), fixture.lines(),
                    observation.analysisStatus(), observation.rejectedModelFindings(),
                    List.copyOf(accepted), matched, extras, missed));
        }
        Double precision = truePositives + falsePositives == 0 ? null
                : (double) truePositives / (truePositives + falsePositives);
        Double recall = truePositives + falseNegatives == 0 ? null
                : (double) truePositives / (truePositives + falseNegatives);
        return new Report(model, Instant.now(),
                new Metrics(observations.size(), positive, clean, available, malformed, unavailable,
                        rejected,
                        truePositives, falsePositives, falseNegatives, precision, recall),
                List.copyOf(scores));
    }

    private EvaluationScorer() {}
}
