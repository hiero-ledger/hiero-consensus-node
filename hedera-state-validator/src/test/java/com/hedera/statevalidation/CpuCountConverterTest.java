// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CpuCountConverterTest {

    @Test
    void parsesIntegerValue() throws Exception {
        final CpuCountConverter c = new CpuCountConverter();
        assertEquals(Integer.valueOf(4), c.convert("4"));
    }

    @Test
    void parsesPercentValue() throws Exception {
        final CpuCountConverter c = new CpuCountConverter();
        final int available = Runtime.getRuntime().availableProcessors();
        // 50% should be at least 1 and at most available
        final Integer computed = c.convert("50%");
        final int expected = Math.max(1, (int) Math.floor(available * 0.5));
        assertEquals(Integer.valueOf(expected), computed);
    }

    @Test
    void rejectsNegativeInteger() {
        final CpuCountConverter c = new CpuCountConverter();
        assertThrows(CommandLine.TypeConversionException.class, () -> c.convert("-1"));
    }

    @Test
    void rejectsMalformedPercent() {
        final CpuCountConverter c = new CpuCountConverter();
        assertThrows(CommandLine.TypeConversionException.class, () -> c.convert("abc%"));
    }
}
