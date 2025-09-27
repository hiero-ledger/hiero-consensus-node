// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file;

import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.ServiceFactory;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Implements the HAPI <a
 * href="https://github.com/hashgraph/hedera-protobufs/blob/main/services/file_service.proto">File
 * Service</a>.
 */
public interface FileService extends RpcService {

    /**
     * The name of the service.
     */
    String NAME = "FileService";

    @NonNull
    @Override
    default String getServiceName() {
        return NAME;
    }

    @NonNull
    @Override
    default Set<RpcServiceDefinition> rpcDefinitions() {
        return Set.of(FileServiceDefinition.INSTANCE);
    }

    /**
     * Returns the concrete implementation instance of the service.
     *
     * @return the implementation instance
     */
    @NonNull
    static FileService getInstance() {
        return ServiceFactory.loadService(FileService.class, ServiceLoader.load(FileService.class));
    }
}
