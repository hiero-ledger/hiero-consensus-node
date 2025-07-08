// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.parameterresolver;

import static com.hedera.statevalidation.parameterresolver.InitUtils.initConfiguration;
import static com.hedera.statevalidation.parameterresolver.InitUtils.initVirtualMapRecord;

public class VirtualMapHolder {
    private static VirtualMapHolder instance;
    private final VirtualMapAndDataSourceRecord record;

    private VirtualMapHolder() {
        initConfiguration();
        try {
            record = initVirtualMapRecord();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static VirtualMapHolder getInstance() {
        instance = (instance == null) ? new VirtualMapHolder() : instance;
        return instance;
    }

    public VirtualMapAndDataSourceRecord getRecord() {
        return record;
    }
}
