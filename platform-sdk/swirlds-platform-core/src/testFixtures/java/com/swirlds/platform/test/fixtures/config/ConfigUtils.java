// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.config;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.FileSystemManagerConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.config.MerkleDbConfig_;
import com.swirlds.platform.config.AddressBookConfig;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.virtualmap.config.VirtualMapConfig;

public final class ConfigUtils {
    private ConfigUtils() {}

    public static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .withConfigDataType(AddressBookConfig.class)
            .withConfigDataType(BasicConfig.class)
            .withConfigDataType(MerkleDbConfig.class)
            .withSource(new SimpleConfigSource().withValue(MerkleDbConfig_.INITIAL_CAPACITY, "" + 65_536L))
            .withConfigDataType(VirtualMapConfig.class)
            .withConfigDataType(TemporaryFileConfig.class)
            .withConfigDataType(StateCommonConfig.class)
            .withConfigDataType(FileSystemManagerConfig.class)
            .build();
}
