// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.transactions;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ED25519_ON;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType.FEE_SCHEDULE_KEY;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType.SUPPLY_KEY;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.SpecEntity;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecToken;
import com.hedera.services.bdd.spec.dsl.operations.AbstractSpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Authorizes a contract to act on behalf of an entity.
 */
public class AuthorizeContractOperation extends AbstractSpecOperation implements SpecOperation {
    private final String managedKeyName;
    private final SpecEntity target;
    private final SpecContract[] contracts;
    private final Set<TokenKeyType> extraTokenAuthorizations = EnumSet.noneOf(TokenKeyType.class);

    // non-standard ArrayList initializer
    @SuppressWarnings({"java:S3599", "java:S1171"})
    public AuthorizeContractOperation(@NonNull final SpecEntity target, @NonNull final SpecContract... contracts) {
        super(new ArrayList<>() {
            {
                add(target);
                addAll(List.of(contracts));
            }
        });
        this.target = target;
        this.contracts = requireNonNull(contracts);
        managedKeyName = target.name() + "_"
                + Arrays.stream(contracts).map(SpecContract::name).collect(joining("|")) + "ManagedKey";
    }

    /**
     * Update this operation to also authorize a given key types besides the admin key.
     * @param keyTypes an additional the key types to authorize
     * @return this
     */
    public AuthorizeContractOperation alsoAuthorizing(@NonNull final TokenKeyType... keyTypes) {
        extraTokenAuthorizations.addAll(Arrays.asList(keyTypes));
        return this;
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        final var controller = managedKeyShape().signedWith(sigControl());
        final var key = spec.keys().generateSubjectTo(spec, controller);
        spec.registry().saveKey(managedKeyName, key);
        return switch (target) {
            case final SpecAccount account -> cryptoUpdate(account.name()).key(managedKeyName);
            case final SpecContract contract -> contractUpdate(contract.name()).newKey(managedKeyName);
            case final SpecToken token -> {
                final var op = tokenUpdate(token.name()).adminKey(managedKeyName);
                if (extraTokenAuthorizations.contains(TokenKeyType.KYC_KEY)) {
                    op.kycKey(managedKeyName);
                }
                if (extraTokenAuthorizations.contains(TokenKeyType.FREEZE_KEY)) {
                    op.freezeKey(managedKeyName);
                }
                if (extraTokenAuthorizations.contains(TokenKeyType.WIPE_KEY)) {
                    op.wipeKey(managedKeyName);
                }
                if (extraTokenAuthorizations.contains(SUPPLY_KEY)) {
                    op.supplyKey(managedKeyName);
                }
                if (extraTokenAuthorizations.contains(FEE_SCHEDULE_KEY)) {
                    op.feeScheduleKey(managedKeyName);
                }
                if (extraTokenAuthorizations.contains(TokenKeyType.PAUSE_KEY)) {
                    op.pauseKey(managedKeyName);
                }
                if (extraTokenAuthorizations.contains(TokenKeyType.METADATA_KEY)) {
                    op.metadataKey(managedKeyName);
                }
                yield op;
            }
            default -> throw new IllegalStateException("Cannot authorize contracts for " + target);
        };
    }

    @Override
    protected void onSuccess(@NonNull final HapiSpec spec) {
        switch (target) {
            case final SpecAccount account -> account.updateKeyFrom(
                    toPbj(spec.registry().getKey(managedKeyName)), spec);
            case final SpecToken token -> {
                // (FUTURE) - update the admin key on the token model
            }
            case final SpecContract contract -> {}
            default -> throw new IllegalStateException("Cannot authorize contract for " + target);
        }
    }

    private KeyShape managedKeyShape() {
        final var shapes = new KeyShape[contracts.length + 1];
        Arrays.fill(shapes, CONTRACT);
        shapes[0] = ED25519;
        return KeyShape.threshOf(1, shapes);
    }

    private Object sigControl() {
        final var controls = new Object[contracts.length + 1];
        controls[0] = ED25519_ON;
        for (var i = 0; i < contracts.length; i++) {
            controls[i + 1] = contracts[i].name();
        }
        return sigs(controls);
    }
}
