// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link HighVolumePricingValidator}.
 */
class HighVolumePricingValidatorTest {

    private static final String SIMPLE_FEES_PATH =
            "hedera-node/hedera-file-service-impl/src/main/resources/genesis/simpleFeesSchedules.json";

    @Test
    @DisplayName("Validates that the actual simpleFeesSchedules.json matches expected pricing")
    void validateActualSimpleFeesSchedules() {
        // Find the project root by looking for the simpleFeesSchedules.json file
        Path projectRoot = findProjectRoot();
        Path simpleFeesPath = projectRoot.resolve(SIMPLE_FEES_PATH);

        if (!Files.exists(simpleFeesPath)) {
            // Skip test if file doesn't exist (e.g., running from a different directory)
            System.out.println("Skipping test - simpleFeesSchedules.json not found at: " + simpleFeesPath);
            return;
        }

        HighVolumePricingValidator validator = new HighVolumePricingValidator(simpleFeesPath);
        assertDoesNotThrow(validator::validate, "Validation should pass for actual simpleFeesSchedules.json");
    }

    @Test
    @DisplayName("Fails validation when pricing curve is incorrect")
    void failsValidationWhenPricingCurveIsIncorrect(@TempDir Path tempDir) throws IOException {
        // Create a simpleFeesSchedules.json with incorrect pricing
        String incorrectJson = """
            {
              "services": [
                {
                  "name": "Crypto",
                  "schedule": [
                    {
                      "name": "CryptoCreate",
                      "baseFee": 499000000,
                      "highVolumeRates": {
                        "maxMultiplier": 200000,
                        "pricingCurve": {
                          "piecewiseLinear": {
                            "points": [
                              { "utilizationBasisPoints": 2, "multiplier": 9999999 }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              ]
            }
            """;

        Path incorrectFile = tempDir.resolve("incorrect-fees.json");
        Files.writeString(incorrectFile, incorrectJson);

        HighVolumePricingValidator validator = new HighVolumePricingValidator(incorrectFile);
        assertThrows(AssertionError.class, validator::validate, "Validation should fail for incorrect pricing curve");
    }

    @Test
    @DisplayName("Fails validation when max multiplier is below 1000")
    void failsValidationWhenMaxMultiplierBelowMinimum(@TempDir Path tempDir) throws IOException {
        String invalidMaxMultiplierJson = """
            {
              "services": [
                {
                  "name": "Crypto",
                  "schedule": [
                    {
                      "name": "CryptoCreate",
                      "baseFee": 499000000,
                      "highVolumeRates": {
                        "maxMultiplier": 999,
                        "pricingCurve": {
                          "piecewiseLinear": {
                            "points": [
                              { "utilizationBasisPoints": 2, "multiplier": 1000 }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              ]
            }
            """;

        Path invalidFile = tempDir.resolve("invalid-max-multiplier.json");
        Files.writeString(invalidFile, invalidMaxMultiplierJson);

        HighVolumePricingValidator validator = new HighVolumePricingValidator(invalidFile);
        assertThrows(
                AssertionError.class, validator::validate, "Validation should fail when max multiplier is below 1000");
    }

    @Test
    @DisplayName("Fails validation when a point multiplier is below 1000")
    void failsValidationWhenPointMultiplierBelowMinimum(@TempDir Path tempDir) throws IOException {
        String invalidPointMultiplierJson = """
            {
              "services": [
                {
                  "name": "Crypto",
                  "schedule": [
                    {
                      "name": "CryptoCreate",
                      "baseFee": 499000000,
                      "highVolumeRates": {
                        "maxMultiplier": 200000,
                        "pricingCurve": {
                          "piecewiseLinear": {
                            "points": [
                              { "utilizationBasisPoints": 2, "multiplier": 999 }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              ]
            }
            """;

        Path invalidFile = tempDir.resolve("invalid-point-multiplier.json");
        Files.writeString(invalidFile, invalidPointMultiplierJson);

        HighVolumePricingValidator validator = new HighVolumePricingValidator(invalidFile);
        assertThrows(
                AssertionError.class,
                validator::validate,
                "Validation should fail when point multiplier is below 1000");
    }

    @Test
    @DisplayName("Fails validation when transaction type is missing")
    void failsValidationWhenTransactionTypeIsMissing(@TempDir Path tempDir) throws IOException {
        // Create a simpleFeesSchedules.json missing a required transaction type
        String missingTxJson = """
            {
              "services": [
                {
                  "name": "Crypto",
                  "schedule": [
                    {
                      "name": "CryptoUpdate",
                      "baseFee": 1200000
                    }
                  ]
                }
              ]
            }
            """;

        Path missingTxFile = tempDir.resolve("missing-tx-fees.json");
        Files.writeString(missingTxFile, missingTxJson);

        HighVolumePricingValidator validator = new HighVolumePricingValidator(missingTxFile);
        assertThrows(
                AssertionError.class,
                validator::validate,
                "Validation should fail when required transaction type is missing");
    }

    private Path findProjectRoot() {
        // Try to find the project root by looking for common markers
        Path current = Path.of("").toAbsolutePath();

        // Walk up the directory tree looking for the hedera-node directory
        while (current != null) {
            if (Files.exists(current.resolve("hedera-node"))) {
                return current;
            }
            current = current.getParent();
        }

        // Default to current directory
        return Path.of("").toAbsolutePath();
    }
}
