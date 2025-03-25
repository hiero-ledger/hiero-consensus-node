// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.entities;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.PBJ_IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.PROTO_IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.atMostOnce;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.withSubstitutedTypes;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createLargeFile;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.contract.Utils.getInitcodeOf;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.OwningEntity;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.EvmAddressableEntity;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.operations.queries.GetBalanceOperation;
import com.hedera.services.bdd.spec.dsl.operations.queries.GetContractInfoOperation;
import com.hedera.services.bdd.spec.dsl.operations.queries.StaticCallContractOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.AssociateTokensOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.AuthorizeContractOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.CallContractOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.DissociateTokensOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.TransferTokenOperation;
import com.hedera.services.bdd.spec.dsl.utils.KeyMetadata;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCreate;
import com.hedera.services.bdd.spec.utilops.grouping.InBlockingOrder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.bouncycastle.util.encoders.Hex;

/**
 * Represents a Hedera account that may exist on one or more target networks and be
 * registered with more than one {@link HapiSpec} if desired.
 */
public class SpecContract extends AbstractSpecEntity<SpecOperation, Account>
        implements OwningEntity, EvmAddressableEntity {
    private static final int MAX_INLINE_INITCODE_SIZE = 4096;
    public static final String VARIANT_NONE = "";
    public static final String VARIANT_16C = "16c";
    public static final String VARIANT_167 = "167";

    private final long creationGas;
    private final String contractName;
    private final boolean immutable;
    private final int maxAutoAssociations;
    private final Account.Builder builder = Account.newBuilder();
    private final String variant;

    /**
     * The constructor arguments for the contract's creation call; if the arguments are
     * not constant values, must be set imperatively within the HapiTest context instead
     * of via @ContractSpec annotation attribute.
     */
    private Object[] constructorArgs = new Object[0];

    /**
     * Creates a new contract model from the given annotation.
     * @param annotation the annotation
     * @return the model
     */
    public static SpecContract contractFrom(@NonNull final Contract annotation) {
        final var name = annotation.name().isBlank() ? annotation.contract() : annotation.name();
        return new SpecContract(
                name,
                annotation.contract(),
                annotation.creationGas(),
                annotation.isImmutable(),
                annotation.maxAutoAssociations(),
                annotation.variant());
    }

    private SpecContract(
            @NonNull final String name,
            @NonNull final String contractName,
            final long creationGas,
            final boolean immutable,
            final int maxAutoAssociations,
            @NonNull final String variant) {
        super(name);
        this.immutable = immutable;
        this.creationGas = creationGas;
        this.contractName = requireNonNull(contractName);
        this.maxAutoAssociations = maxAutoAssociations;
        this.variant = requireNonNull(variant);
    }

    /**
     * Returns a builder for the model account to be created, or throws if the entity is locked.
     *
     * @return the builder
     */
    public Account.Builder builder() {
        throwIfLocked();
        return builder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Address addressOn(@NonNull final HederaNetwork network) {
        requireNonNull(network);
        final var networkContract = contractOrThrow(network);
        return headlongAddressOf(networkContract);
    }

    /**
     * Returns an operation to get the balance of the account.
     *
     * @return the operation
     */
    public GetBalanceOperation getBalance() {
        return new GetBalanceOperation(this);
    }

    /**
     * Returns an operation that retrieves the contract information.
     *
     * @return the operation
     */
    public GetContractInfoOperation getInfo() {
        return new GetContractInfoOperation(this);
    }

    /**
     * Returns an operation to associate the contract with the given tokens. Will ultimately fail if the contract
     * does not have an admin key.
     *
     * @param tokens the tokens to associate
     * @return the operation
     */
    public AssociateTokensOperation associateTokens(@NonNull final SpecToken... tokens) {
        requireNonNull(tokens);
        return new AssociateTokensOperation(this, List.of(tokens));
    }

    /**
     * Returns an operation to dissociate the contract with the given tokens.
     *
     * @param tokens the tokens to dissociate
     * @return the operation
     */
    public DissociateTokensOperation dissociateTokens(@NonNull final SpecToken... tokens) {
        requireNonNull(tokens);
        return new DissociateTokensOperation(this, List.of(tokens));
    }

    /**
     * Returns an operation to associate the contract with the given tokens. Will ultimately fail if the contract
     * does not have an admin key.
     *
     * @param token the tokens to associate
     * @return the operation
     */
    public TransferTokenOperation receiveUnitsFrom(
            @NonNull final SpecAccount sender, @NonNull final SpecToken token, final long amount) {
        requireNonNull(token);
        requireNonNull(sender);
        return new TransferTokenOperation(amount, token, sender, this);
    }

    /**
     * Returns an operation that calls a function on the contract.
     *
     * @param function the function name
     * @param parameters the function parameters
     * @return the operation
     */
    public CallContractOperation call(@NonNull final String function, @NonNull final Object... parameters) {
        return new CallContractOperation(this, function, parameters);
    }

    /**
     * Returns an operation that static calls a function on the contract.
     *
     * @param function the function name
     * @param parameters the function parameters
     * @return the operation
     */
    public StaticCallContractOperation staticCall(@NonNull final String function, @NonNull final Object... parameters) {
        return new StaticCallContractOperation(this, function, parameters);
    }

    /**
     * Sets the constructor arguments for the contract's creation call.
     *
     * @param args the arguments
     */
    public void setConstructorArgs(@NonNull final Object... args) {
        constructorArgs = args;
    }

    /**
     * Gets the contract model for the given network, or throws if it doesn't exist.
     *
     * @param network the network
     * @return the contract model
     */
    public Account contractOrThrow(@NonNull final HederaNetwork network) {
        return modelOrThrow(network);
    }

    /**
     * Returns an operation to authorize the given contract to act on behalf of this account.
     *
     * @param contract the contract to authorize
     * @return the operation
     */
    public AuthorizeContractOperation authorizeContract(@NonNull final SpecContract contract) {
        requireNonNull(contract);
        return new AuthorizeContractOperation(this, contract);
    }

    /**
     * Returns the variant of the contract.
     * @return the variant a\
     */
    public String variant() {
        return variant;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Creation<SpecOperation, Account> newCreation(@NonNull final HapiSpec spec) {
        final var model = builder.build();
        final var initcode = getInitcodeOf(contractName, variant);
        final SpecOperation op;
        constructorArgs = withSubstitutedTypes(spec.targetNetworkOrThrow(), constructorArgs);
        if (initcode.size() < MAX_INLINE_INITCODE_SIZE) {
            final var unhexedBytecode = Hex.decode(initcode.toByteArray());
            op = contractCreate(name, constructorArgs)
                    .gas(creationGas)
                    .maxAutomaticTokenAssociations(maxAutoAssociations)
                    .inlineInitCode(ByteString.copyFrom(unhexedBytecode))
                    .omitAdminKey(immutable);
        } else {
            op = blockingOrder(
                    createLargeFile(GENESIS, contractName, initcode),
                    contractCreate(name, constructorArgs)
                            .gas(creationGas)
                            .maxAutomaticTokenAssociations(maxAutoAssociations)
                            .bytecode(contractName)
                            .omitAdminKey(immutable));
        }
        return new Creation<>(op, model);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Result<Account> resultForSuccessful(
            @NonNull final Creation<SpecOperation, Account> creation, @NonNull final HapiSpec spec) {
        final HapiContractCreate contractCreate;
        if (creation.op() instanceof final HapiContractCreate inlineCreate) {
            contractCreate = inlineCreate;
        } else {
            contractCreate = (HapiContractCreate) ((InBlockingOrder) creation.op()).last();
        }

        final var newContractNum = contractCreate.numOfCreatedContractOrThrow();
        final var maybeKeyMetadata = contractCreate.getAdminKey().map(key -> KeyMetadata.from(key, spec));
        return new Result<>(
                creation.model()
                        .copyBuilder()
                        .smartContract(true)
                        .accountId(AccountID.newBuilder()
                                .shardNum(Long.parseLong(SHARD))
                                .realmNum(Long.parseLong(REALM))
                                .accountNum(newContractNum)
                                .build())
                        .key(maybeKeyMetadata.map(KeyMetadata::pbjKey).orElse(PBJ_IMMUTABILITY_SENTINEL_KEY))
                        .build(),
                atMostOnce(siblingSpec -> {
                    maybeKeyMetadata.ifPresentOrElse(
                            keyMetadata -> keyMetadata.registerAs(name, siblingSpec),
                            () -> siblingSpec.registry().saveKey(name, PROTO_IMMUTABILITY_SENTINEL_KEY));
                    siblingSpec
                            .registry()
                            .saveAccountId(
                                    name,
                                    com.hederahashgraph.api.proto.java.AccountID.newBuilder()
                                            .setShardNum(Long.parseLong(SHARD))
                                            .setRealmNum(Long.parseLong(REALM))
                                            .setAccountNum(newContractNum)
                                            .build());
                    siblingSpec
                            .registry()
                            .saveContractId(
                                    name,
                                    com.hederahashgraph.api.proto.java.ContractID.newBuilder()
                                            .setShardNum(Long.parseLong(SHARD))
                                            .setRealmNum(Long.parseLong(REALM))
                                            .setContractNum(newContractNum)
                                            .build());
                    siblingSpec.registry().saveContractInfo(name, contractCreate.infoOfCreatedContractOrThrow());
                }));
    }
}
