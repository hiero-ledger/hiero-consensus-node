// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging;

import com.swirlds.logging.api.Marker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MarkerTest {

    @Test
    void testNullName() {
        Assertions.assertThrows(NullPointerException.class, () -> new Marker(null));
    }

    @Test
    void testSimpelMarker() {
        // given
        Marker marker = new Marker("markerName");

        // then
        Assertions.assertEquals("markerName", marker.name());
        Assertions.assertNull(marker.previous());
    }

    @Test
    void testWithParenMarker() {
        // given
        Marker parent = new Marker("parentName");
        Marker marker = new Marker("markerName", parent);

        // then
        Assertions.assertEquals("markerName", marker.name());
        Assertions.assertNotNull(marker.previous());
        Assertions.assertSame(parent, marker.previous());
        Assertions.assertNull(parent.previous());
    }

    @Test
    void testEquals() {
        // given
        Marker marker1 = new Marker("marker1");
        Marker marker2 = new Marker("marker2");
        Marker marker3 = new Marker("marker1");
        Marker marker4 = new Marker("marker1", marker2);

        // then
        Assertions.assertEquals(marker1, marker1);
        Assertions.assertEquals(marker1, marker3);
        Assertions.assertNotEquals(marker1, marker2);
        Assertions.assertNotEquals(marker1, null);
        Assertions.assertNotEquals(marker1, marker4);
    }

    @Test
    void testHashCode() {
        // given
        Marker marker1 = new Marker("marker1");
        Marker marker2 = new Marker("marker2");
        Marker marker3 = new Marker("marker1");
        Marker marker4 = new Marker("marker1", marker2);

        // then
        Assertions.assertEquals(marker1.hashCode(), marker1.hashCode());
        Assertions.assertEquals(marker1.hashCode(), marker3.hashCode());
        Assertions.assertNotEquals(marker1.hashCode(), marker2.hashCode());
        Assertions.assertNotEquals(marker1.hashCode(), marker4.hashCode());
    }
}
