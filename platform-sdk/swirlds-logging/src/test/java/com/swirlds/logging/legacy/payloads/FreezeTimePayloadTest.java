// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payloads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.swirlds.logging.legacy.payload.AbstractLogPayload;
import com.swirlds.logging.legacy.payload.ApplicationDualStatePayload;
import com.swirlds.logging.legacy.payload.SetFreezeTimePayload;
import com.swirlds.logging.legacy.payload.SetLastFrozenTimePayload;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FreezeTimePayloadTest {
    @Test
    void freezeTimePayloadTest() {
        Instant now = Instant.now();
        Instant next = now.plusSeconds(100);

        // Test SetFreezeTimePayload
        SetFreezeTimePayload setFreezeTimePayload = new SetFreezeTimePayload(now);
        assertEquals(now, setFreezeTimePayload.getFreezeTime(), "Freezetime should match NOW timestamp");

        setFreezeTimePayload.setFreezeTime(next);
        assertEquals(next, setFreezeTimePayload.getFreezeTime(), "Freezetime should match NEXT timestamp");

        // Test SetLastFrozenTimePayload
        SetLastFrozenTimePayload setLastFrozenTimePayload = new SetLastFrozenTimePayload(now);
        assertEquals(now, setLastFrozenTimePayload.getLastFrozenTime(), "LastFrozenTime should match NOW timestamp");

        setLastFrozenTimePayload.setLastFrozenTime(next);
        assertEquals(next, setLastFrozenTimePayload.getLastFrozenTime(), "LastFrozenTime should match NEXT timestamp");

        // Test ApplicationDualStatePayload
        ApplicationDualStatePayload applicationDualStatePayload = new ApplicationDualStatePayload(now, null);
        assertNull(applicationDualStatePayload.getLastFrozenTime());
        assertEquals(now, applicationDualStatePayload.getFreezeTime(), "Freezetime should match NOW timestamp");

        applicationDualStatePayload.setFreezeTime(next);
        assertEquals(next, applicationDualStatePayload.getFreezeTime(), "Freezetime should match NEXT timestamp");

        applicationDualStatePayload.setLastFrozenTime(next);
        assertEquals(
                next, applicationDualStatePayload.getLastFrozenTime(), "LastFrozenTime should match NEXT timestamp");
    }

    @Test
    void serializationTest() {
        SetFreezeTimePayload setFreezeTimePayload = new SetFreezeTimePayload(Instant.now());
        SetFreezeTimePayload parsedsetFreezeTimePayload =
                AbstractLogPayload.parsePayload(SetFreezeTimePayload.class, setFreezeTimePayload.toString());
        assertEquals(
                parsedsetFreezeTimePayload.getFreezeTime(),
                setFreezeTimePayload.getFreezeTime(),
                "Freezetime should match");

        SetLastFrozenTimePayload setLastFrozenTimePayload = new SetLastFrozenTimePayload(Instant.now());
        SetLastFrozenTimePayload parsedsetLastFrozenTimePayload =
                AbstractLogPayload.parsePayload(SetLastFrozenTimePayload.class, setLastFrozenTimePayload.toString());
        assertEquals(
                parsedsetLastFrozenTimePayload.getLastFrozenTime(),
                setLastFrozenTimePayload.getLastFrozenTime(),
                "LastFrozenTime should match");

        ApplicationDualStatePayload applicationDualStatePayload =
                new ApplicationDualStatePayload(Instant.now(), Instant.now().plusSeconds(100));
        ApplicationDualStatePayload parsedApplicationDualStatePayload = AbstractLogPayload.parsePayload(
                ApplicationDualStatePayload.class, applicationDualStatePayload.toString());
        assertEquals(
                parsedApplicationDualStatePayload.getFreezeTime(),
                applicationDualStatePayload.getFreezeTime(),
                "Freezetime should match");
        assertEquals(
                parsedApplicationDualStatePayload.getLastFrozenTime(),
                applicationDualStatePayload.getLastFrozenTime(),
                "LastFrozenTime should match");
    }
}
