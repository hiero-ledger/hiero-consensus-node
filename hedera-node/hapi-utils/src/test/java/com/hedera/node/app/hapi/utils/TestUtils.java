// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.node.app.hapi.utils.sysfiles.serdes.ThrottlesJsonToProtoSerde;
import java.io.IOException;

public class TestUtils {
    public static ThrottleDefinitions protoDefs(final String testResource) throws IOException {
        try (final var in = ThrottlesJsonToProtoSerde.class.getClassLoader().getResourceAsStream(testResource)) {
            return ThrottlesJsonToProtoSerde.loadProtoDefs(in);
        }
    }

    public static com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ThrottleDefinitions pojoDefs(
            final String testResource) throws IOException {
        try (final var in = ThrottlesJsonToProtoSerde.class.getClassLoader().getResourceAsStream(testResource)) {
            return ThrottlesJsonToProtoSerde.loadPojoDefs(in);
        }
    }
}
