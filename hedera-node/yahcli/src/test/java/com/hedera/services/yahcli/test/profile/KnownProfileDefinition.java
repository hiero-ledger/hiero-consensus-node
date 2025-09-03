// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.profile;

import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoCreate;
import com.hedera.services.bdd.spec.utilops.inventory.NewSpecKey;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;

public interface KnownProfileDefinition {
    /**
     * Name of the profile (used as a prefix to name the account, key, etc.)
     */
    String name();

    /**
     * A new account creation operation for the profile, exposing the created account number
     * to the given reference.
     */
    HapiCryptoCreate newAccount(@NonNull final AtomicReference<Long> civAcctNum);

    /**
     * Initial balance funded to the account
     */
    long initialBalance();

    /**
     * Builder for the civilian's key (exported to a pem file in the keys dir)
     */
    NewSpecKey newKey();
}
