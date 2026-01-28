// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.impl.calculator.NodeCreateFeeCalculator;
import com.hedera.node.app.service.addressbook.impl.calculator.NodeDeleteFeeCalculator;
import com.hedera.node.app.service.addressbook.impl.calculator.NodeUpdateFeeCalculator;
import com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema;
import com.hedera.node.app.service.addressbook.impl.schemas.V068AddressBookSchema;
import com.hedera.node.app.spi.RpcService;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Standard implementation of the {@link AddressBookService} {@link RpcService}.
 */
public final class AddressBookServiceImpl implements AddressBookService {

    @Override
    public void registerSchemas(@NonNull SchemaRegistry registry) {
        registry.register(new V053AddressBookSchema());
        registry.register(new V068AddressBookSchema());
    }

    @Override
    public Set<ServiceFeeCalculator> serviceFeeCalculators() {
        return Set.of(new NodeCreateFeeCalculator(), new NodeUpdateFeeCalculator(), new NodeDeleteFeeCalculator());
    }
}
