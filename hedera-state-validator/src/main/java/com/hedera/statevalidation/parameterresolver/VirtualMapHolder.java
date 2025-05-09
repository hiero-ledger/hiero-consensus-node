/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
