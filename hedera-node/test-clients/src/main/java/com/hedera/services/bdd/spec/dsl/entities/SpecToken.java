// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.entities;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.atMostOnce;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.EvmAddressableEntity;
import com.hedera.services.bdd.spec.dsl.SpecEntity;
import com.hedera.services.bdd.spec.dsl.contracts.TokenRedirectContract;
import com.hedera.services.bdd.spec.dsl.operations.queries.GetTokenInfoOperation;
import com.hedera.services.bdd.spec.dsl.operations.queries.StaticCallTokenOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.AuthorizeContractOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.CallTokenOperation;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Represents a Hedera token that may exist on one or more target networks and be
 * registered with more than one {@link HapiSpec} if desired.
 */
public class SpecToken extends AbstractSpecEntity<HapiTokenCreate, Token> implements SpecEntity, EvmAddressableEntity {
    public static final String DEFAULT_TREASURY_NAME_SUFFIX = "Treasury";
    public static final String DEFAULT_AUTO_RENEW_ACCOUNT_NAME_SUFFIX = "AutoRenew";

    protected final Token.Builder builder = Token.newBuilder();

    @Nullable
    private SpecAccount autoRenewAccount;

    private SpecAccount treasuryAccount;

    @Nullable
    private Set<SpecTokenKey> keys;

    public SpecToken(@NonNull final String name, @NonNull final TokenType tokenType) {
        super(name);
        builder.tokenType(tokenType);
        treasuryAccount = new SpecAccount(name + DEFAULT_TREASURY_NAME_SUFFIX);
    }

    /**
     * Customizes the given token with the given keys, auto-renew account setting.
     * @param token the token to customize
     * @param keys the role keys to use with the token
     * @param useAutoRenewAccount whether to use an auto-renew account
     */
    public static void customizeToken(
            @NonNull final SpecToken token, @NonNull final SpecTokenKey[] keys, final boolean useAutoRenewAccount) {
        token.setKeys(EnumSet.copyOf(List.of(keys)));
        if (useAutoRenewAccount) {
            token.useAutoRenewAccount();
        }
    }

    /**
     * Returns an operation that calls a redirect function on the token "contract".
     *
     * @param redirectContract the redirect contract
     * @param function the function name
     * @param parameters the function parameters
     * @return the operation
     */
    public CallTokenOperation call(
            @NonNull final TokenRedirectContract redirectContract,
            @NonNull final String function,
            @NonNull final Object... parameters) {
        return new CallTokenOperation(this, redirectContract, function, parameters);
    }

    /**
     * Returns an operation that static calls a redirect function on the token "contract".
     *
     * @param redirectContract the redirect contract
     * @param function the function name
     * @param parameters the function parameters
     * @return the operation
     */
    public StaticCallTokenOperation staticCall(
            @NonNull final TokenRedirectContract redirectContract,
            @NonNull final String function,
            @NonNull final Object... parameters) {
        return new StaticCallTokenOperation(this, redirectContract, function, parameters);
    }

    /**
     * Indicates this token should use an auto-renew account.
     */
    public void useAutoRenewAccount() {
        autoRenewAccount = new SpecAccount(name + DEFAULT_AUTO_RENEW_ACCOUNT_NAME_SUFFIX);
    }

    /**
     * Returns an operation that retrieves the token information.
     *
     * @return the operation
     */
    public GetTokenInfoOperation getInfo() {
        return new GetTokenInfoOperation(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<SpecEntity> prerequisiteEntities() {
        final List<SpecEntity> prerequisites = new ArrayList<>();
        prerequisites.add(treasuryAccount);
        if (autoRenewAccount != null) {
            prerequisites.add(autoRenewAccount);
        }
        return prerequisites;
    }

    /**
     * Returns a builder for the model token to be created, or throws if the entity is locked.
     *
     * @return the builder
     */
    public Token.Builder builder() {
        throwIfLocked();
        return builder;
    }

    /**
     * Gets the token model for the given network, or throws if it doesn't exist.
     *
     * @param network the network
     * @return the token model
     */
    public Token tokenOrThrow(@NonNull final HederaNetwork network) {
        return modelOrThrow(network);
    }

    /**
     * Sets the account that will pay for auto-renewals of the token.
     *
     * @param autoRenewAccount the account
     */
    public void setAutoRenewAccount(@NonNull final SpecAccount autoRenewAccount) {
        this.autoRenewAccount = requireNonNull(autoRenewAccount);
    }

    /**
     * Sets the types of keys to associate with the token.
     *
     * @param keys the types of keys to associate with the token
     */
    public void setKeys(@NonNull final Set<SpecTokenKey> keys) {
        this.keys = requireNonNull(keys);
    }

    /**
     * Gets the treasury account.
     *
     * @return the treasury account
     */
    public SpecAccount treasury() {
        return treasuryAccount;
    }

    /**
     * Gets the auto-renew account.
     *
     * @return the auto-renew account
     */
    public SpecAccount autoRenewAccount() {
        return autoRenewAccount;
    }

    /**
     * Returns an operation to authorize the given contracts to act on behalf of this token.
     *
     * @param contracts the contracts to authorize
     * @return the operation
     */
    public AuthorizeContractOperation authorizeContracts(@NonNull final SpecContract... contracts) {
        requireNonNull(contracts);
        return new AuthorizeContractOperation(this, contracts);
    }

    /**
     * Sets the treasury account.
     *
     * @param treasuryAccount the treasury account
     */
    public void setTreasury(@NonNull final SpecAccount treasuryAccount) {
        this.treasuryAccount = requireNonNull(treasuryAccount);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Address addressOn(@NonNull HederaNetwork network) {
        requireNonNull(network);
        final var networkToken = tokenOrThrow(network);
        return headlongAddressOf(networkToken.tokenIdOrThrow());
    }

    @Override
    public String toString() {
        return "SpecToken{" + "name='" + name + '\'' + '}';
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Creation<HapiTokenCreate, Token> newCreation(@NonNull final HapiSpec spec) {
        final var model = builder.build();
        final var op = tokenCreate(name)
                .tokenType(com.hederahashgraph.api.proto.java.TokenType.forNumber(
                        builder.build().tokenType().protoOrdinal()))
                .treasury(requireNonNull(treasuryAccount).name())
                .maxSupply(model.maxSupply())
                .supplyType(model.maxSupply() == 0 ? TokenSupplyType.INFINITE : TokenSupplyType.FINITE)
                .initialSupply(model.totalSupply());
        if (autoRenewAccount != null) {
            op.autoRenewAccount(autoRenewAccount.name());
        }
        if (keys != null) {
            keys.forEach(key -> generateKeyInContext(key, spec, op));
        }
        return new Creation<>(op, builder.build());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Result<Token> resultForSuccessful(
            @NonNull final Creation<HapiTokenCreate, Token> creation, @NonNull final HapiSpec spec) {
        final var newTokenNum = creation.op().numOfCreatedTokenOrThrow();
        final var allKeyMetadata = creation.op().allCreatedKeyMetadata(spec);
        return new Result<>(
                creation.model()
                        .copyBuilder()
                        .tokenId(TokenID.newBuilder()
                                .shardNum(Long.parseLong(SHARD))
                                .realmNum(Long.parseLong(REALM))
                                .tokenNum(newTokenNum)
                                .build())
                        .build(),
                atMostOnce(siblingSpec -> {
                    allKeyMetadata.forEach(keyMetadata -> keyMetadata.registerAs(name, siblingSpec));
                    siblingSpec
                            .registry()
                            .saveTokenId(
                                    name,
                                    com.hederahashgraph.api.proto.java.TokenID.newBuilder()
                                            .setShardNum(Long.parseLong(SHARD))
                                            .setRealmNum(Long.parseLong(REALM))
                                            .setTokenNum(newTokenNum)
                                            .build());
                    creation.op().registerAttributes(siblingSpec);
                    siblingSpec
                            .registry()
                            .saveContractId(
                                    name,
                                    ContractID.newBuilder()
                                            .setShardNum(Long.parseLong(SHARD))
                                            .setRealmNum(Long.parseLong(REALM))
                                            .setContractNum(newTokenNum)
                                            .build());
                }));
    }

    private void generateKeyInContext(
            @NonNull final SpecTokenKey tokenKey, @NonNull final HapiSpec spec, @NonNull final HapiTokenCreate op) {
        final var key = spec.keys().generateSubjectTo(spec, SigControl.ON);
        final var keyName = name + "_" + tokenKey;
        spec.registry().saveKey(keyName, key);
        switch (tokenKey) {
            case ADMIN_KEY -> {
                op.adminKey(keyName);
                builder.adminKey(toPbj(key));
            }
            case KYC_KEY -> {
                op.kycKey(keyName);
                builder.kycKey(toPbj(key));
            }
            case FREEZE_KEY -> {
                op.freezeKey(keyName);
                builder.freezeKey(toPbj(key));
            }
            case WIPE_KEY -> {
                op.wipeKey(keyName);
                builder.wipeKey(toPbj(key));
            }
            case SUPPLY_KEY -> {
                op.supplyKey(keyName);
                builder.supplyKey(toPbj(key));
            }
            case FEE_SCHEDULE_KEY -> {
                op.feeScheduleKey(keyName);
                builder.feeScheduleKey(toPbj(key));
            }
            case PAUSE_KEY -> {
                op.pauseKey(keyName);
                builder.pauseKey(toPbj(key));
            }
            case METADATA_KEY -> {
                op.metadataKey(keyName);
                builder.metadataKey(toPbj(key));
            }
        }
    }
}
