// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema;
import com.hedera.node.app.service.addressbook.impl.schemas.V068AddressBookSchema;
import com.hedera.node.app.spi.RpcService;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Standard implementation of the {@link AddressBookService} {@link RpcService}.
 */
public final class AddressBookServiceImpl implements AddressBookService {

    @Override
    public void registerSchemas(@NonNull SchemaRegistry registry) {
        registry.register(new V053AddressBookSchema());
        registry.register(new V068AddressBookSchema());
    }
}
