// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Validates that the high-volume pricing curves in simpleFeesSchedules.json match
 * the expected values from the HIP-1313 specification.
 *
 * <p>The validator reads the expected pricing data from expected-high-volume-pricing.json
 * and compares it against the actual pricing curves in simpleFeesSchedules.json.
 */
public class HighVolumePricingValidator {

    private static final Logger logger = LogManager.getLogger(HighVolumePricingValidator.class);

    private static final String EXPECTED_PRICING_RESOURCE = "/testSystemFiles/expected-high-volume-pricing.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path simpleFeesPath;

    // Conversion constants matching the proto definitions
    private static final int UTILIZATION_SCALE = 100_000; // thousandths of percent
    private static final int MULTIPLIER_SCALE = 1_000_000; // multiplier - 1, scaled by 1M

    /**
     * Creates a validator with the path to the simpleFeesSchedules.json file.
     *
     * @param simpleFeesPath path to the simpleFeesSchedules.json file
     */
    public HighVolumePricingValidator(Path simpleFeesPath) {
        this.simpleFeesPath = simpleFeesPath;
    }

    /**
     * Validates that the high-volume pricing curves in simpleFeesSchedules.json
     * match the expected values from the HIP-1313 specification.
     *
     * @throws AssertionError if any pricing curve doesn't match expectations
     */
    public void validate() {
        try {
            final var expectedPricing = loadExpectedPricing();
            final var actualFeeSchedule = loadActualFeeSchedule();

            validatePricingCurves(expectedPricing, actualFeeSchedule);

            logger.info("High-volume pricing validation passed for all transaction types");
        } catch (IOException e) {
            fail("Failed to load pricing data: " + e.getMessage());
        }
    }

    private ExpectedPricing loadExpectedPricing() throws IOException {
        try (InputStream is = getClass().getResourceAsStream(EXPECTED_PRICING_RESOURCE)) {
            assertNotNull(is, "Expected pricing file not found: " + EXPECTED_PRICING_RESOURCE);
            return parseExpectedPricing(MAPPER.readTree(is));
        }
    }

    private JsonNode loadActualFeeSchedule() throws IOException {
        return MAPPER.readTree(Files.readString(simpleFeesPath));
    }

    private ExpectedPricing parseExpectedPricing(JsonNode root) {
        final var baseCurve = new ArrayList<BaseCurvePoint>();
        final var baseCurveNode = root.get("baseCurve").get("points");
        for (JsonNode point : baseCurveNode) {
            baseCurve.add(new BaseCurvePoint(
                    point.get("rateMultiplier").asDouble(),
                    point.get("priceMultiplier").asInt()));
        }

        final var transactionTypes = new ArrayList<TransactionTypeSpec>();
        for (JsonNode txType : root.get("transactionTypes")) {
            transactionTypes.add(new TransactionTypeSpec(
                    txType.get("name").asText(),
                    txType.get("baseRate").asInt(),
                    txType.get("maxTps").asInt(),
                    txType.get("basePrice").asDouble()));
        }

        return new ExpectedPricing(baseCurve, transactionTypes);
    }

    private void validatePricingCurves(ExpectedPricing expected, JsonNode actualFeeSchedule) {
        // Build a map of transaction name -> actual pricing curve
        final var actualCurves = extractActualCurves(actualFeeSchedule);

        for (TransactionTypeSpec txSpec : expected.transactionTypes()) {
            final var txName = txSpec.name();
            assertTrue(actualCurves.containsKey(txName),
                    "Missing high-volume pricing for transaction: " + txName);

            final var actualCurve = actualCurves.get(txName);
            validateTransactionCurve(txSpec, expected.baseCurve(), actualCurve);
        }
    }

    private Map<String, ActualPricingCurve> extractActualCurves(JsonNode feeSchedule) {
        final var curves = new HashMap<String, ActualPricingCurve>();

        for (JsonNode service : feeSchedule.get("services")) {
            for (JsonNode feeDef : service.get("schedule")) {
                final var name = feeDef.get("name").asText();
                final var highVolumeRates = feeDef.get("highVolumeRates");
                if (highVolumeRates != null) {
                    final var maxMultiplier = highVolumeRates.get("maxMultiplier").asInt();
                    final var points = new ArrayList<ActualPoint>();
                    final var piecewiseLinear = highVolumeRates.get("pricingCurve").get("piecewiseLinear");
                    for (JsonNode point : piecewiseLinear.get("points")) {
                        points.add(new ActualPoint(
                                point.get("utilizationPercentage").asInt(),
                                point.get("multiplier").asInt()));
                    }
                    curves.put(name, new ActualPricingCurve(maxMultiplier, points));
                }
            }
        }
        return curves;
    }

    private void validateTransactionCurve(
            TransactionTypeSpec txSpec,
            List<BaseCurvePoint> baseCurve,
            ActualPricingCurve actualCurve) {
        final var txName = txSpec.name();
        final var baseRate = txSpec.baseRate();
        final var maxTps = txSpec.maxTps();

        // Calculate expected points from base curve
        final var expectedPoints = calculateExpectedPoints(baseCurve, baseRate, maxTps);

        // Validate max multiplier - it should be the multiplier at the last point
        final var expectedMaxMultiplier = expectedPoints.getLast().multiplier();
        assertEquals(expectedMaxMultiplier, actualCurve.maxMultiplier(),
                txName + ": maxMultiplier mismatch (expected " + expectedMaxMultiplier
                        + ", actual " + actualCurve.maxMultiplier() + ")");

        // Validate each point
        assertEquals(expectedPoints.size(), actualCurve.points().size(),
                txName + ": number of points mismatch");

        for (int i = 0; i < expectedPoints.size(); i++) {
            final var expected = expectedPoints.get(i);
            final var actual = actualCurve.points().get(i);

            assertEquals(expected.utilizationPercentage(), actual.utilizationPercentage(),
                    txName + " point " + i + ": utilizationPercentage mismatch");
            assertEquals(expected.multiplier(), actual.multiplier(),
                    txName + " point " + i + ": multiplier mismatch");
        }

        logger.info("Validated pricing curve for {}: {} points", txName, expectedPoints.size());
    }

    private List<ActualPoint> calculateExpectedPoints(
            List<BaseCurvePoint> baseCurve, int baseRate, int maxTps) {
        final var points = new ArrayList<ActualPoint>();

        for (BaseCurvePoint curvePoint : baseCurve) {
            // Calculate the effective TPS at this point on the curve
            final var effectiveTps = curvePoint.rateMultiplier() * baseRate;

            // Skip points that exceed max TPS
            if (effectiveTps > maxTps) {
                continue;
            }

            // Calculate utilization percentage (in thousandths of percent)
            final var utilizationPercentage = (int) Math.round((effectiveTps / maxTps) * UTILIZATION_SCALE);

            // Calculate multiplier value
            final var multiplier = calculateExpectedMultiplier(curvePoint.priceMultiplier());

            points.add(new ActualPoint(utilizationPercentage, multiplier));
        }

        // Add final point at 100% utilization if not already there
        if (points.isEmpty() || points.getLast().utilizationPercentage() < UTILIZATION_SCALE) {
            // Find the max price multiplier that applies at 100% utilization
            final var maxPriceMultiplier = getMaxPriceMultiplierAtUtilization(baseCurve, baseRate, maxTps);
            points.add(new ActualPoint(UTILIZATION_SCALE, calculateExpectedMultiplier(maxPriceMultiplier)));
        }

        return points;
    }

    private int getMaxPriceMultiplierAtUtilization(List<BaseCurvePoint> baseCurve, int baseRate, int maxTps) {
        int maxPriceMultiplier = 4; // Default starting multiplier
        for (BaseCurvePoint point : baseCurve) {
            final var effectiveTps = point.rateMultiplier() * baseRate;
            if (effectiveTps <= maxTps) {
                maxPriceMultiplier = point.priceMultiplier();
            }
        }
        return maxPriceMultiplier;
    }

    private int calculateExpectedMultiplier(int priceMultiplier) {
        // multiplier = (priceMultiplier - 1) * 1,000,000
        return (priceMultiplier - 1) * MULTIPLIER_SCALE;
    }

    // Record classes for data structures
    record BaseCurvePoint(double rateMultiplier, int priceMultiplier) {}

    record TransactionTypeSpec(String name, int baseRate, int maxTps, double basePrice) {}

    record ExpectedPricing(List<BaseCurvePoint> baseCurve, List<TransactionTypeSpec> transactionTypes) {}

    record ActualPoint(int utilizationPercentage, int multiplier) {}

    record ActualPricingCurve(int maxMultiplier, List<ActualPoint> points) {}

    /**
     * Main method for standalone validation.
     *
     * @param args command line arguments - first argument should be path to simpleFeesSchedules.json
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: HighVolumePricingValidator <path-to-simpleFeesSchedules.json>");
            System.exit(1);
        }
        new HighVolumePricingValidator(Path.of(args[0])).validate();
    }
}

