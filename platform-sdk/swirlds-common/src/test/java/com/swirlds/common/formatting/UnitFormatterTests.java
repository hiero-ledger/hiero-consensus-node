// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.formatting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.units.DataUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UnitFormatter Tests")
class UnitFormatterTests {

    @Test
    @DisplayName("Format Longs Test")
    void formatLongsTest() {
        assertEquals("0.0 b", DataUnit.UNIT_KILOBYTES.buildFormatter().render());
        assertEquals(
                "0.0 b",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter()
                        .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                        .render());
        assertEquals("1.0 KB", DataUnit.UNIT_KILOBYTES.buildFormatter(1).render());
        assertEquals(
                "1.0 KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1)
                        .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                        .render());
        assertEquals(
                "1.0 kilobyte",
                DataUnit.UNIT_KILOBYTES.buildFormatter(1).setAbbreviate(false).render());
        assertEquals(
                "7.0 kilobytes",
                DataUnit.UNIT_KILOBYTES.buildFormatter(7).setAbbreviate(false).render());
        assertEquals(
                "0.0",
                DataUnit.UNIT_KILOBYTES.buildFormatter().setShowUnit(false).render());
        assertEquals(
                "1.0",
                DataUnit.UNIT_KILOBYTES.buildFormatter(1).setShowUnit(false).render());
        assertEquals(
                "1.0KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1)
                        .setShowSpaceInBetween(false)
                        .render());
        assertEquals(
                "7.0KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(7)
                        .setShowSpaceInBetween(false)
                        .render());
        assertEquals(
                "1 KB",
                DataUnit.UNIT_KILOBYTES.buildFormatter(1).setDecimalPlaces(0).render());
        assertEquals(
                "7 KB",
                DataUnit.UNIT_KILOBYTES.buildFormatter(7).setDecimalPlaces(0).render());
        assertEquals(
                "1.00000 KB",
                DataUnit.UNIT_KILOBYTES.buildFormatter(1).setDecimalPlaces(5).render());
        assertEquals(
                "7.00000 KB",
                DataUnit.UNIT_KILOBYTES.buildFormatter(7).setDecimalPlaces(5).render());
        assertEquals(
                "7.00000 KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(7)
                        .setDecimalPlaces(5)
                        .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                        .render());
        assertEquals("2.0 MB", DataUnit.UNIT_KILOBYTES.buildFormatter(1024 * 2).render());
        assertEquals(
                "2,048.0 KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1024 * 2)
                        .setUnitFormat(UnitFormat.UNSIMPLIFIED)
                        .render());
        assertEquals(
                "2.0 MB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1024 * 2)
                        .setUnitFormat(UnitFormat.SINGLE_SIMPLIFIED)
                        .render());
        assertEquals(
                "2.0 MB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1024 * 2)
                        .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                        .render());
        assertEquals(
                "1 TB 2 MB 523.00 KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1024 * 1024 * 1024 + 2048 + 523)
                        .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                        .setDecimalPlaces(2)
                        .render());
        assertEquals(
                "1 GB 2 MB 523 KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1024 * 1024 + 2048 + 523)
                        .setDecimalPlaces(0)
                        .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                        .render());
        assertEquals(
                "1 GB 3 MB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1024 * 1024 + 2048 + 523)
                        .setDecimalPlaces(0)
                        .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                        .setLowestUnit(DataUnit.UNIT_MEGABYTES)
                        .render());
        assertEquals(
                "1 GB 2.51 MB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1024 * 1024 + 2048 + 523)
                        .setDecimalPlaces(2)
                        .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                        .setLowestUnit(DataUnit.UNIT_MEGABYTES)
                        .render());
        assertEquals(
                "1 GB 2 MB 523.0 KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1024 * 1024 + 2048 + 523)
                        .setDecimalPlaces(1)
                        .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                        .setUnitCount(4)
                        .render());
        assertEquals(
                "1 PB 1 TB 1 GB 2.51 MB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(
                                (1024L * 1024 * 1024 * 1024) + (1024 * 1024 * 1024) + (1024 * 1024) + 2048 + 523)
                        .setDecimalPlaces(2)
                        .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                        .setUnitCount(4)
                        .render());
    }

    @Test
    @DisplayName("Format Doubles Test")
    void formatDoublesTest() {
        assertEquals(
                "0.0 b",
                DataUnit.UNIT_KILOBYTES.buildFormatter().setQuantity(0.0).render());
        assertEquals("1.0 KB", DataUnit.UNIT_KILOBYTES.buildFormatter(1.0).render());
        assertEquals(
                "1.0 kilobyte",
                DataUnit.UNIT_KILOBYTES.buildFormatter(1.0).setAbbreviate(false).render());
        assertEquals(
                "7.0 kilobytes",
                DataUnit.UNIT_KILOBYTES.buildFormatter(7.0).setAbbreviate(false).render());
        assertEquals(
                "0.0",
                DataUnit.UNIT_KILOBYTES.buildFormatter().setShowUnit(false).render());
        assertEquals(
                "1.0",
                DataUnit.UNIT_KILOBYTES.buildFormatter(1.0).setShowUnit(false).render());
        assertEquals(
                "1.0KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1.0)
                        .setShowSpaceInBetween(false)
                        .render());
        assertEquals(
                "7.0KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(7.0)
                        .setShowSpaceInBetween(false)
                        .render());
        assertEquals(
                "1 KB",
                DataUnit.UNIT_KILOBYTES.buildFormatter(1.0).setDecimalPlaces(0).render());
        assertEquals(
                "7 KB",
                DataUnit.UNIT_KILOBYTES.buildFormatter(7.0).setDecimalPlaces(0).render());
        assertEquals(
                "1.00000 KB",
                DataUnit.UNIT_KILOBYTES.buildFormatter(1.0).setDecimalPlaces(5).render());
        assertEquals(
                "7.00000 KB",
                DataUnit.UNIT_KILOBYTES.buildFormatter(7.0).setDecimalPlaces(5).render());
        assertEquals(
                "2.0 MB", DataUnit.UNIT_KILOBYTES.buildFormatter(1024 * 2.0).render());
        assertEquals(
                "2,048.0 KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1024 * 2.0)
                        .setUnitFormat(UnitFormat.UNSIMPLIFIED)
                        .render());
        assertEquals(
                "9.877 KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(9.87654321)
                        .setDecimalPlaces(3)
                        .render());
        assertEquals(
                "10 KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(9.87654321)
                        .setDecimalPlaces(0)
                        .render());
        assertEquals(
                "9.877 kilobytes",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(9.87654321)
                        .setDecimalPlaces(3)
                        .setAbbreviate(false)
                        .render());
        assertEquals(
                "2 megabytes 651.55 kilobytes",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1024 * 2 + 651.54698)
                        .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                        .setDecimalPlaces(2)
                        .setAbbreviate(false)
                        .render());
        assertEquals(
                "2MB 652KB",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1024 * 2 + 651.54698)
                        .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                        .setDecimalPlaces(0)
                        .setShowSpaceInBetween(false)
                        .render());

        // Edge case: number that isn't 1.0 but rounds to 1.0
        assertEquals(
                "1.000 kilobyte",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1.0000001)
                        .setDecimalPlaces(3)
                        .setAbbreviate(false)
                        .render());
        assertEquals(
                "1.0 kilobyte",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1.0000001)
                        .setAbbreviate(false)
                        .render());
        assertEquals(
                "1 kilobyte",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1.0000001)
                        .setDecimalPlaces(0)
                        .setAbbreviate(false)
                        .render());
        assertEquals(
                "1 kilobyte",
                DataUnit.UNIT_KILOBYTES
                        .buildFormatter(1.0000001)
                        .setDecimalPlaces(0)
                        .setAbbreviate(false)
                        .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                        .render());
    }
}
