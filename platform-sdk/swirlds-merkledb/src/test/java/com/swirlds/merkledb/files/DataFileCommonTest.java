// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.files.DataFileCommon.formatSizeBytes;
import static com.swirlds.merkledb.files.DataFileCommon.roundTwoDecimals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DataFileCommonTest {

    @ParameterizedTest
    @CsvSource({"1.2345,1.23", "12.345,12.34", "123.3,123.3"})
    void roundsAsExpected(final double in, final double out) {
        assertEquals(out, roundTwoDecimals(in), 0.01, "Should have rounded off more than two digits of precision");
    }

    @ParameterizedTest
    @CsvSource({"48,48 bytes", "2048,2.0 KB", "20482048,19.53 MB", "204820482048,190.75 GB"})
    void formatsAsExpected(final long size, final String desc) {
        assertEquals(desc, formatSizeBytes(size), "Should recognize the largest size denomination relevant");
    }
}
