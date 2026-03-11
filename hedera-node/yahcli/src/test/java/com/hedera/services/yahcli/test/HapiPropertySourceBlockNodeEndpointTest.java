// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.spec.HapiPropertySource;
import org.junit.jupiter.api.Test;

class HapiPropertySourceBlockNodeEndpointTest {

    @Test
    void throwsWhenBlockNodeApiIsMissing() {
        final var ex = assertThrows(
                IllegalArgumentException.class, () -> HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080"));
        assertTrue(ex.getMessage().contains("Missing required blockNodeApi"));
    }

    @Test
    void throwsWhenOnlyTlsIsProvidedWithoutBlockNodeApi() {
        final var ex = assertThrows(
                IllegalArgumentException.class, () -> HapiPropertySource.asBlockNodeEndpoint("127.0.0.1:8080:tls"));
        assertTrue(ex.getMessage().contains("Missing required blockNodeApi"));
    }
}
