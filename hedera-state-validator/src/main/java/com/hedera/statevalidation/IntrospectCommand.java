// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.statevalidation.introspector.KvIntrospector;
import com.hedera.statevalidation.introspector.SingletonIntrospector;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.State;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "introspect", description = "Introspects the state.")
public class IntrospectCommand implements Runnable {

    @ParentCommand
    private StateOperatorCommand parent;

    @Parameters(index = "0", description = "Service name.")
    private String serviceName;

    @Parameters(index = "1", description = "State key.")
    private String stateKey;

    @Parameters(index = "2", arity = "0..1", description = "Key info - KeyType:<Payload as JSON>")
    private String keyInfo;

    @Override
    public void run() {
        parent.initializeStateDir();

        final State state;
        try {
            final DeserializedSignedState deserializedSignedState = StateUtils.getDeserializedSignedState();
            state = deserializedSignedState.reservedSignedState().get().getState();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        final int stateId = StateUtils.stateIdFor(serviceName, stateKey);
        if (keyInfo == null) {
            // we assume it's a singleton
            final SingletonIntrospector introspector = new SingletonIntrospector(state, serviceName, stateId);
            introspector.introspect();
        } else {
            final KvIntrospector introspector = new KvIntrospector(state, serviceName, stateId, keyInfo);
            introspector.introspect();
        }
    }
}
