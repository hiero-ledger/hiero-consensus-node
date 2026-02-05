// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.accounts;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.otter.fixtures.app.model.Account;
import org.hiero.otter.fixtures.app.model.AccountId;
import org.hiero.otter.fixtures.app.model.EntityIdGenerator;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;
import org.hiero.otter.fixtures.app.state.OtterStateId;

public class AccountsStateSpecification implements OtterServiceStateSpecification {

    private static final int ENTITYID_GENERATOR_STATE_ID = OtterStateId.ENTITYID_GENERATOR_STATE_ID.id();
    private static final String ENTITYID_GENERATOR_STATE_KEY = "ENTITYID_GENERATOR_STATE";

    private static final int ACCOUNTS_STATE_ID = OtterStateId.ACCOUNTS_STATE_ID.id();
    private static final String ACCOUNTS_STATE_KEY = "ACCOUNTS_STATE";
    private static final long MAX_ACCOUNTS = 1 << 30;

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Set<StateDefinition<?, ?>> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(
                        ENTITYID_GENERATOR_STATE_ID, ENTITYID_GENERATOR_STATE_KEY, EntityIdGenerator.PROTOBUF),
                StateDefinition.onDisk(
                        ACCOUNTS_STATE_ID, ACCOUNTS_STATE_KEY, AccountId.PROTOBUF, Account.PROTOBUF, MAX_ACCOUNTS));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDefaultValues(@NonNull final WritableStates states, @NonNull final SemanticVersion version) {
        states.getSingleton(OtterStateId.ENTITYID_GENERATOR_STATE_ID.id()).put(new EntityIdGenerator(1));
    }
}
