// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.embedded;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.addressbook.AddressBookService.NAME;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.swirlds.state.spi.ReadableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

public class ViewNodeOp extends UtilOp {

    private final String nodeName;
    private final Consumer<Node> observer;

    public ViewNodeOp(@NonNull final String nodeName, @NonNull final Consumer<Node> observer) {
        this.nodeName = requireNonNull(nodeName);
        this.observer = requireNonNull(observer);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final var state = spec.embeddedStateOrThrow();
        final var readableStates = state.getReadableStates(NAME);
        final ReadableKVState<EntityNumber, Node> nodes = readableStates.get(NODES_STATE_ID);
        final var nodeId = toPbj(TxnUtils.asNodeId(nodeName, spec));
        final var node = nodes.get(nodeId);
        observer.accept(node);
        return false;
    }
}
