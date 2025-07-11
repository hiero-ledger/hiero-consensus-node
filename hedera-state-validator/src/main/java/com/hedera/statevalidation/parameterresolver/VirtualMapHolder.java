// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.parameterresolver;

import static com.hedera.statevalidation.parameterresolver.InitUtils.initConfiguration;
import static com.hedera.statevalidation.parameterresolver.InitUtils.initServiceRegistry;
import static com.hedera.statevalidation.parameterresolver.InitUtils.initVirtualMapRecords;
import static com.hedera.statevalidation.validators.Constants.STATE_DIR;

import com.hedera.node.app.services.ServicesRegistryImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VirtualMapHolder {

    private static VirtualMapHolder instance;
    private final List<VirtualMapAndDataSourceRecord<?, ?>> records;
    private final List<String> tableNames;

    private VirtualMapHolder() {
        initConfiguration();

        final ServicesRegistryImpl servicesRegistry = initServiceRegistry();

        final Path stateDirPath = Paths.get(STATE_DIR);

        try {
            tableNames = initTableNames(stateDirPath);
            records = initVirtualMapRecords(servicesRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> initTableNames(Path stateDirPath) throws IOException {
        final Path dataDir = stateDirPath.resolve("data");
        if (Files.isDirectory(dataDir)) {
            try (final Stream<Path> s = Files.list(dataDir)) {
                return s.filter(Files::isDirectory)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .collect(Collectors.toList());
            }
        }
        throw new RuntimeException("Failed to get table names");
    }

    public static VirtualMapHolder getInstance() {
        instance = (instance == null) ? new VirtualMapHolder() : instance;
        return instance;
    }

    public List<String> getTableNames() {
        return tableNames;
    }

    public List<VirtualMapAndDataSourceRecord<?, ?>> getRecords() {
        return records;
    }
}
