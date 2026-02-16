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
    private static final String SIMPLE_FEES_PATH =
            "hedera-node/hedera-file-service-impl/src/main/resources/genesis/simpleFeesSchedules.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path simpleFeesPath;

    // Conversion constants matching the proto definitions
    private static final int UTILIZATION_SCALE = 10_000; // hundredths of percent (basis points)
    private static final int MULTIPLIER_SCALE = 1_000; // multiplier * 1000

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
            // rateMultiplierTenths is a fixed-point integer (e.g., 15 = 1.5x) to avoid floating-point precision issues
            baseCurve.add(new BaseCurvePoint(
                    point.get("rateMultiplierTenths").asInt(),
                    point.get("priceMultiplier").asInt()));
        }

        final var transactionTypes = new ArrayList<TransactionTypeSpec>();
        for (JsonNode txType : root.get("transactionTypes")) {
            // basePriceMills is price in thousandths of a dollar (e.g., 50 = $0.05) to avoid floating-point precision
            // issues
            transactionTypes.add(new TransactionTypeSpec(
                    txType.get("name").asText(),
                    txType.get("baseRate").asInt(),
                    txType.get("maxTps").asInt(),
                    txType.get("basePriceMills").asInt()));
        }

        return new ExpectedPricing(baseCurve, transactionTypes);
    }

    private void validatePricingCurves(ExpectedPricing expected, JsonNode actualFeeSchedule) {
        // Build a map of transaction name -> actual pricing curve
        final var actualCurves = extractActualCurves(actualFeeSchedule);

        for (TransactionTypeSpec txSpec : expected.transactionTypes()) {
            final var txName = txSpec.name();
            assertTrue(actualCurves.containsKey(txName), "Missing high-volume pricing for transaction: " + txName);

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
                    final var maxMultiplier =
                            highVolumeRates.get("maxMultiplier").asInt();
                    assertTrue(
                            maxMultiplier >= MULTIPLIER_SCALE,
                            name + ": maxMultiplier must be at least " + MULTIPLIER_SCALE);
                    final var points = new ArrayList<ActualPoint>();
                    final var piecewiseLinear =
                            highVolumeRates.get("pricingCurve").get("piecewiseLinear");
                    int lastUtilization = -1;
                    int pointIndex = 0;
                    for (JsonNode point : piecewiseLinear.get("points")) {
                        final var utilizationBasisPoints =
                                point.get("utilizationBasisPoints").asInt();
                        if (pointIndex > 0) {
                            assertTrue(
                                    utilizationBasisPoints > lastUtilization,
                                    name + " point " + pointIndex
                                            + ": utilizationBasisPoints must be strictly ascending (prev "
                                            + lastUtilization + ", current " + utilizationBasisPoints + ")");
                        }
                        lastUtilization = utilizationBasisPoints;
                        pointIndex++;

                        final var multiplier = point.get("multiplier").asInt();
                        assertTrue(
                                multiplier >= MULTIPLIER_SCALE,
                                name + ": point multiplier must be at least " + MULTIPLIER_SCALE);
                        points.add(new ActualPoint(utilizationBasisPoints, multiplier));
                    }
                    curves.put(name, new ActualPricingCurve(maxMultiplier, points));
                }
            }
        }
        return curves;
    }

    private void validateTransactionCurve(
            TransactionTypeSpec txSpec, List<BaseCurvePoint> baseCurve, ActualPricingCurve actualCurve) {
        final var txName = txSpec.name();
        final var baseRate = txSpec.baseRate();
        final var maxTps = txSpec.maxTps();

        // Calculate expected points from base curve
        final var expectedPoints = calculateExpectedPoints(baseCurve, baseRate, maxTps);

        // Validate max multiplier - it should be the multiplier at the last point
        final var expectedMaxMultiplier = expectedPoints.getLast().multiplier();
        assertEquals(
                expectedMaxMultiplier,
                actualCurve.maxMultiplier(),
                txName + ": maxMultiplier mismatch (expected " + expectedMaxMultiplier + ", actual "
                        + actualCurve.maxMultiplier() + ")");

        // Validate each point
        assertEquals(expectedPoints.size(), actualCurve.points().size(), txName + ": number of points mismatch");

        for (int i = 0; i < expectedPoints.size(); i++) {
            final var expected = expectedPoints.get(i);
            final var actual = actualCurve.points().get(i);

            assertEquals(
                    expected.utilizationBasisPoints(),
                    actual.utilizationBasisPoints(),
                    txName + " point " + i + ": utilizationBasisPoints mismatch");
            assertEquals(expected.multiplier(), actual.multiplier(), txName + " point " + i + ": multiplier mismatch");
        }

        logger.info("Validated pricing curve for {}: {} points", txName, expectedPoints.size());
    }

    private List<ActualPoint> calculateExpectedPoints(List<BaseCurvePoint> baseCurve, int baseRate, int maxTps) {
        final var points = new ArrayList<ActualPoint>();

        for (BaseCurvePoint curvePoint : baseCurve) {
            // Calculate the effective TPS at this point on the curve
            // rateMultiplierTenths is in tenths (e.g., 15 = 1.5x), so we multiply by baseRate and divide by 10
            // Use long arithmetic to avoid overflow (e.g., 50000 * 5 * 10000 would overflow int)
            final long effectiveTpsTenths = (long) curvePoint.rateMultiplierTenths() * baseRate;
            final long maxTpsTenths = (long) maxTps * 10;

            // Skip points that exceed max TPS
            if (effectiveTpsTenths > maxTpsTenths) {
                continue;
            }

            // Calculate utilization in basis points (hundredths of percent)
            // Using long arithmetic with rounding: (effectiveTpsTenths * UTILIZATION_SCALE + maxTpsTenths/2) /
            // maxTpsTenths
            final int utilizationBasisPoints =
                    (int) ((effectiveTpsTenths * UTILIZATION_SCALE + maxTpsTenths / 2) / maxTpsTenths);

            // Calculate multiplier value (price * 1000)
            final var multiplier = calculateExpectedMultiplier(curvePoint.priceMultiplier());

            points.add(new ActualPoint(utilizationBasisPoints, multiplier));
        }

        // Add final point at 100% utilization if not already there
        if (points.isEmpty() || points.getLast().utilizationBasisPoints() < UTILIZATION_SCALE) {
            // Find the max price multiplier that applies at 100% utilization
            final var maxPriceMultiplier = getMaxPriceMultiplierAtUtilization(baseCurve, baseRate, maxTps);
            points.add(new ActualPoint(UTILIZATION_SCALE, calculateExpectedMultiplier(maxPriceMultiplier)));
        }

        return points;
    }

    private int getMaxPriceMultiplierAtUtilization(List<BaseCurvePoint> baseCurve, int baseRate, int maxTps) {
        int maxPriceMultiplier = 4; // Default starting multiplier
        final long maxTpsTenths = (long) maxTps * 10;
        for (BaseCurvePoint point : baseCurve) {
            final long effectiveTpsTenths = (long) point.rateMultiplierTenths() * baseRate;
            if (effectiveTpsTenths <= maxTpsTenths) {
                maxPriceMultiplier = point.priceMultiplier();
            }
        }
        return maxPriceMultiplier;
    }

    private int calculateExpectedMultiplier(int priceMultiplier) {
        // multiplier = priceMultiplier * 1000
        return priceMultiplier * MULTIPLIER_SCALE;
    }

    // Record classes for data structures
    // rateMultiplierTenths is in tenths (e.g., 15 = 1.5x) to avoid floating-point precision issues
    record BaseCurvePoint(int rateMultiplierTenths, int priceMultiplier) {}

    // basePriceMills is price in thousandths of a dollar (e.g., 50 = $0.05) to avoid floating-point precision issues
    record TransactionTypeSpec(String name, int baseRate, int maxTps, int basePriceMills) {}

    record ExpectedPricing(List<BaseCurvePoint> baseCurve, List<TransactionTypeSpec> transactionTypes) {}

    record ActualPoint(int utilizationBasisPoints, int multiplier) {}

    record ActualPricingCurve(int maxMultiplier, List<ActualPoint> points) {}

    /**
     * Validates the genesis simpleFeesSchedules.json file against expected HIP-1313 pricing curves.
     * This method is designed to be called at test startup to ensure the fee schedule is correct.
     *
     * @throws AssertionError if validation fails
     */
    public static void validateGenesisFeeSchedule() {
        final Path genesisFeesPath = Path.of(SIMPLE_FEES_PATH);
        if (!Files.exists(genesisFeesPath)) {
            logger.error(
                    "Genesis simpleFeesSchedules.json not found at {}, skipping high-volume pricing validation",
                    genesisFeesPath);
            return;
        }
        logger.info("Validating high-volume pricing curves in genesis fee schedule");
        new HighVolumePricingValidator(genesisFeesPath).validate();
    }

    /**
     * Main method for standalone validation.
     *
     * @param args command line arguments - first argument should be path to simpleFeesSchedules.json
     */
    public static void main(String[] args) {
        validateGenesisFeeSchedule();
    }
}
