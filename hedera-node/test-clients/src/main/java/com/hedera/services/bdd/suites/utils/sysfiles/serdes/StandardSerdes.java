// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.sysfiles.serdes;

import java.util.Map;

public class StandardSerdes {

    /**
     * Private constructor to prevent instantiation.
     *
     * @throws UnsupportedOperationException if this class is instantiated via reflection.
     */
    private StandardSerdes() {
        throw new UnsupportedOperationException();
    }

    public static final Map<Long, SysFileSerde<String>> SYS_FILE_SERDES = Map.of(
            101L,
            new AddrBkJsonToGrpcBytes(),
            102L,
            new NodesJsonToGrpcBytes(),
            111L,
            new FeesJsonToGrpcBytes(),
            112L,
            new XRatesJsonToGrpcBytes(),
            121L,
            new JutilPropsToSvcCfgBytes("application.properties"),
            122L,
            new JutilPropsToSvcCfgBytes("api-permission.properties"),
            123L,
            new ThrottlesJsonToGrpcBytes());
}
