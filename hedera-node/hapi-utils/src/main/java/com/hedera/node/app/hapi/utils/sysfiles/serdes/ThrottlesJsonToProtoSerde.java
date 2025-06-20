// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.serdes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hederahashgraph.api.proto.java.ThrottleDefinitions;
import java.io.IOException;
import java.io.InputStream;

public final class ThrottlesJsonToProtoSerde {
    private ThrottlesJsonToProtoSerde() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public static ThrottleDefinitions loadProtoDefs(InputStream in) throws IOException {
        return loadPojoDefs(in).toProto();
    }

    public static com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ThrottleDefinitions loadPojoDefs(
            InputStream in) throws IOException {
        var om = new ObjectMapper();
        return om.readValue(in, com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ThrottleDefinitions.class);
    }
}
