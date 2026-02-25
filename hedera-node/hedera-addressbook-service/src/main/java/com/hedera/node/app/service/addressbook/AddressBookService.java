// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook;

import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.ServiceFactory;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Implements the HAPI <a
 * href="https://github.com/hashgraph/hedera-protobufs/blob/main/services/address_book_service.proto">Address Book Service</a>.
 */
public interface AddressBookService extends RpcService {

    String NAME = "AddressBookService";

    @NonNull
    @Override
    default String getServiceName() {
        return NAME;
    }

    @NonNull
    @Override
    default Set<RpcServiceDefinition> rpcDefinitions() {
        return Set.of(AddressBookServiceDefinition.INSTANCE);
    }

    /**
     * Returns the concrete implementation instance of the service.
     *
     * @return the implementation instance
     */
    @NonNull
    static AddressBookService getInstance() {
        return ServiceFactory.loadService(AddressBookService.class, ServiceLoader.load(AddressBookService.class));
    }

    /**
     * A sort value for the service, make sure this service migrate is after TokenService and FileService migrate.
     */
    @Override
    default int migrationOrder() {
        return 1;
    }
}
