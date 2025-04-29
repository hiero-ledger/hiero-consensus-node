// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.entities;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.atMostOnce;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.suites.HapiSuite.TINY_PARTS_PER_WHOLE;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.OwningEntity;
import com.hedera.services.bdd.spec.dsl.EvmAddressableEntity;
import com.hedera.services.bdd.spec.dsl.operations.queries.GetAccountInfoOperation;
import com.hedera.services.bdd.spec.dsl.operations.queries.GetBalanceOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.AirdropOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.ApproveAllowanceOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.AssociateTokensOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.AuthorizeContractOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.CryptoTransferOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.DeleteAccountOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.DissociateTokensOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.TransferTokensOperation;
import com.hedera.services.bdd.spec.dsl.utils.KeyMetadata;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoCreate;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Represents a Hedera account that may exist on one or more target networks and be
 * registered with more than one {@link HapiSpec} if desired.
 */
public class SpecAccount extends AbstractSpecEntity<HapiCryptoCreate, Account>
        implements OwningEntity, EvmAddressableEntity {
    private static final long UNSPECIFIED_CENT_BALANCE = -1;

    private final Account.Builder builder = Account.newBuilder();

    private long centBalance = UNSPECIFIED_CENT_BALANCE;
    private com.hederahashgraph.api.proto.java.Key keyProto;

    public SpecAccount(@NonNull final String name) {
        super(name);
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
     * Sets the initial balance of the account in USD cents, to be converted to tinybars at the target network's
     * active exchange rate at the time of creating this account.
     *
     * @param centBalance the initial balance in cents
     * @return {@code this}
     */
    public SpecAccount centBalance(final long centBalance) {
        throwIfLocked();
        this.centBalance = centBalance;
        return this;
    }

    /**
     * Returns an operation to transfer tokens, transferring its balance to the given beneficiary.
     *
     * @param to the beneficiary
     * @param units the number of units to transfer
     * @param token the token to transfer
     * @return the operation
     */
    public TransferTokensOperation transferUnitsTo(
            @NonNull final SpecAccount to, final long units, @NonNull final SpecFungibleToken token) {
        requireNonNull(token);
        requireNonNull(to);
        return new TransferTokensOperation(this, to, token, units);
    }

    /**
     * Returns an operation to transfer tokens, transferring its balance to the given beneficiary.
     *
     * @param to the beneficiary
     * @param units the number of units to transfer
     * @param token the token to transfer
     * @return the operation
     */
    public TransferTokensOperation transferUnitsTo(
            @NonNull final SpecContract to, final long units, @NonNull final SpecFungibleToken token) {
        requireNonNull(token);
        requireNonNull(to);
        return new TransferTokensOperation(this, to, token, units);
    }

    /**
     * Returns an operation to transfer NFT, transferring the NFT to the given beneficiary.
     *
     * @param to the beneficiary
     * @param serialNumber the specific serial number of the NFT
     * @param token the NFT to transfer
     * @return the operation
     */
    public TransferTokensOperation transferNFTsTo(
            @NonNull final SpecContract to, @NonNull final SpecNonFungibleToken token, final long... serialNumber) {
        requireNonNull(token);
        requireNonNull(to);
        return new TransferTokensOperation(this, to, token, serialNumber);
    }

    /**
     * Returns an operation to transfer NFT, transferring the NFT to the given beneficiary.
     *
     * @param to the beneficiary
     * @param serialNumber the specific serial number of the NFT
     * @param token the NFT to transfer
     * @return the operation
     */
    public TransferTokensOperation transferNFTsTo(
            @NonNull final SpecAccount to, @NonNull final SpecNonFungibleToken token, final long... serialNumber) {
        requireNonNull(token);
        requireNonNull(to);
        return new TransferTokensOperation(this, to, token, serialNumber);
    }

    /**
     * Returns an operation to perform the given airdrops.
     *
     * @param airdrops the airdrops
     * @return the operation
     */
    public AirdropOperation doAirdrops(@NonNull final AirdropOperation.Airdrop... airdrops) {
        requireNonNull(airdrops);
        return new AirdropOperation(this, List.of(airdrops));
    }

    /**
     * Returns an operation to transfer tokens, transferring its balance to the given beneficiary.
     *
     * @param to the beneficiary contract
     * @param amount the amount of hBars to transfer
     * @return the operation
     */
    public CryptoTransferOperation transferHBarsTo(@NonNull final SpecContract to, final long amount) {
        requireNonNull(to);
        return new CryptoTransferOperation(amount, this, to);
    }

    /**
     * Returns an operation to delete the account, transferring its balance to the given beneficiary.
     *
     * @param beneficiary the beneficiary
     * @return the operation
     */
    public DeleteAccountOperation deleteWithTransfer(@NonNull final SpecAccount beneficiary) {
        requireNonNull(beneficiary);
        return new DeleteAccountOperation(this, beneficiary);
    }

    /**
     * Returns an operation to associate the account with the given tokens.
     *
     * @param tokens the tokens to associate
     * @return the operation
     */
    public AssociateTokensOperation associateTokens(@NonNull final SpecToken... tokens) {
        requireNonNull(tokens);
        return new AssociateTokensOperation(this, List.of(tokens));
    }

    /**
     * Returns an operation to approve an allowance for the given contract to spend the given amount of tokens.
     *
     * @param token the token
     * @param spender the contract to approve
     * @param amount the amount to approve
     * @return the operation
     */
    public ApproveAllowanceOperation approveTokenAllowance(
            @NonNull final SpecToken token, @NonNull final SpecContract spender, final long amount) {
        requireNonNull(token);
        return new ApproveAllowanceOperation(token, this, spender, amount);
    }

    /**
     * Returns an operation to approve an allowance for the given contract to spend the given amount of hBars.
     *
     * @param spender the contract to approve
     * @param amount the amount to approve
     * @return the operation
     */
    public ApproveAllowanceOperation approveCryptoAllowance(@NonNull final SpecContract spender, final long amount) {
        return new ApproveAllowanceOperation(this, spender, amount);
    }

    /**
     * Returns an operation to approve an allowance for the given contract to spend the given NFTs.
     *
     * @param token the token
     * @param spender the contract to approve
     * @param serials the serials to approve
     * @return the operation
     */
    public ApproveAllowanceOperation approveNFTAllowance(
            @NonNull final SpecToken token,
            @NonNull final SpecContract spender,
            final boolean approvedForAll,
            @NonNull final List<Long> serials) {
        requireNonNull(token);
        return new ApproveAllowanceOperation(token, this, spender, approvedForAll, serials);
    }

    /**
     * Returns an operation to dissociate the account with the given tokens.
     *
     * @param tokens the tokens to dissociate
     * @return the operation
     */
    public DissociateTokensOperation dissociateTokens(@NonNull final SpecToken... tokens) {
        requireNonNull(tokens);
        return new DissociateTokensOperation(this, List.of(tokens));
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
     * Returns an operation to get the balance of the account.
     *
     * @return the operation
     */
    public GetBalanceOperation getBalance() {
        return new GetBalanceOperation(this);
    }

    /**
     * Returns an operation to get the info of the account.
     *
     * @return the operation
     */
    public GetAccountInfoOperation getInfo() {
        return new GetAccountInfoOperation(this);
    }

    /**
     * Returns the proto key of the account.
     * @return the proto key
     */
    public com.hederahashgraph.api.proto.java.Key getKeyProto() {
        return keyProto;
    }

    /**
     * Returns the bytes of the ED25519 key of the account.
     * @return the bytes of the ED25519 key
     */
    public byte[] getED25519KeyBytes() {
        return getKeyProto().getEd25519().toByteArray();
    }

    /**
     * Gets the account model for the given network, or throws if it doesn't exist.
     *
     * @param network the network
     * @return the account model
     */
    public Account accountOrThrow(@NonNull final HederaNetwork network) {
        return modelOrThrow(network);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Address addressOn(@NonNull final HederaNetwork network) {
        requireNonNull(network);
        final var networkAccount = accountOrThrow(network);
        return headlongAddressOf(networkAccount);
    }

    @Override
    public String toString() {
        return "SpecAccount{" + "name='" + name + '\'' + '}';
    }

    /**
     * Updates the key of the account on the given network.
     *
     * @param key the new key
     * @param spec the active spec targeting the network
     */
    public void updateKeyFrom(@NonNull final Key key, @NonNull final HapiSpec spec) {
        requireNonNull(key);
        requireNonNull(spec);
        final var network = spec.targetNetworkOrThrow();
        final var networkAccount = accountOrThrow(network);
        replaceResult(network, resultFor(networkAccount.copyBuilder().key(key).build(), spec));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Creation<HapiCryptoCreate, Account> newCreation(@NonNull final HapiSpec spec) {
        final var model = builder.build();
        final var op = cryptoCreate(name).maxAutomaticTokenAssociations(model.maxAutoAssociations());
        if (centBalance != UNSPECIFIED_CENT_BALANCE) {
            op.balance(spec.ratesProvider().toTbWithActiveRates(centBalance * TINY_PARTS_PER_WHOLE));
        } else {
            op.balance(model.tinybarBalance());
        }
        if (model.hasStakedNodeId()) {
            op.stakedNodeId(model.stakedNodeIdOrThrow());
        }
        return new Creation<>(op, model);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Result<Account> resultForSuccessful(
            @NonNull final Creation<HapiCryptoCreate, Account> creation, @NonNull final HapiSpec spec) {
        return resultFor(
                creation.model()
                        .copyBuilder()
                        .accountId(AccountID.newBuilder()
                                .shardNum(spec.shard())
                                .realmNum(spec.realm())
                                .accountNum(creation.op().numOfCreatedAccount())
                                .build())
                        .key(toPbj(creation.op().getKey()))
                        .build(),
                spec);
    }

    private Result<Account> resultFor(@NonNull final Account model, @NonNull final HapiSpec spec) {
        final var keyMetadata = KeyMetadata.from(fromPbj(model.keyOrThrow()), spec);
        return new Result<>(model, atMostOnce(siblingSpec -> {
            keyMetadata.registerAs(name, siblingSpec);
            this.keyProto = keyMetadata.protoKey();
            siblingSpec
                    .registry()
                    .saveAccountId(
                            name,
                            com.hederahashgraph.api.proto.java.AccountID.newBuilder()
                                    .setShardNum(model.accountIdOrThrow().shardNum())
                                    .setRealmNum(model.accountIdOrThrow().realmNum())
                                    .setAccountNum(model.accountIdOrThrow().accountNumOrThrow())
                                    .build());
            if (model.receiverSigRequired()) {
                siblingSpec.registry().saveSigRequirement(name, Boolean.TRUE);
            }
        }));
    }
}
