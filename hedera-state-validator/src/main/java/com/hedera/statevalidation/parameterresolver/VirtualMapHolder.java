// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.parameterresolver;

import static com.hedera.statevalidation.parameterresolver.InitUtils.initConfiguration;
import static com.hedera.statevalidation.parameterresolver.InitUtils.initServiceRegistry;
import static com.hedera.statevalidation.parameterresolver.InitUtils.initVirtualMapRecords;
import static com.hedera.statevalidation.validators.Constants.STATE_DIR;

import com.hedera.node.app.services.ServicesRegistryImpl;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.merkledb.MerkleDbTableConfig;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VirtualMapHolder {
    private static VirtualMapHolder instance;
    private final List<VirtualMapAndDataSourceRecord<?, ?>> records;
    private final Map<String, MerkleDbTableConfig> tableConfigByNames;

    private VirtualMapHolder() {
        initConfiguration();

        final ServicesRegistryImpl servicesRegistry = initServiceRegistry();

        final Path stateDirPath = Paths.get(STATE_DIR);
        final MerkleDb merkleDb = MerkleDb.getInstance(stateDirPath, InitUtils.CONFIGURATION);
        tableConfigByNames = merkleDb.getTableConfigs();

        try {
            records = initVirtualMapRecords(servicesRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static VirtualMapHolder getInstance() {
        instance = (instance == null) ? new VirtualMapHolder() : instance;
        return instance;
    }

    public List<String> getTableNames() {
        return new ArrayList<>(tableConfigByNames.keySet());
    }

    public List<VirtualMapAndDataSourceRecord<?, ?>> getRecords() {
        return records;
    }

    public Map<String, MerkleDbTableConfig> getTableConfigByNames() {
        return tableConfigByNames;
    }
}
