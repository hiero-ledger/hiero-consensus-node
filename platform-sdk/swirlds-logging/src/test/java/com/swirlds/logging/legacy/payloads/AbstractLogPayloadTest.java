// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payloads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.logging.legacy.payload.AbstractLogPayload;
import com.swirlds.logging.legacy.payload.PayloadParsingException;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class AbstractLogPayloadTest {

    /**
     * Simple unit test for bean
     */
    @Test
    @Tag(TestComponentTags.LOGGING)
    void payloadErrors() {
        assertEquals("", AbstractLogPayload.extractPayloadType(""), "Should be empty");

        AbstractLogPayload payload = new AbstractLogPayload() {};
        assertThrows(IllegalArgumentException.class, () -> payload.setMessage("{"), "Braces should be illegal");

        assertThrows(
                PayloadParsingException.class,
                () -> AbstractLogPayload.parsePayload(AbstractLogPayload.class, ""),
                "Should throw if no data");
    }
}
