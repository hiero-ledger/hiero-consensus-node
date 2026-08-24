// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hedera.node.app.service.token.api.AccountSummariesApi.SENTINEL_NODE_ID;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Modifier;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates the account staking-configuration calls of
 * <a href="https://github.com/hiero-ledger/hiero-improvement-proposals/pull/1522">HIP-1522</a> to the Hedera
 * Account Service system contract.
 *
 * <p>Each function comes in two forms. The {@code IHRC632} facade form names no account and targets the account
 * the facade was called on; because the redirect that implements the facade fires only for an address carrying
 * no contract bytecode, that form is reachable only for an EOA. The {@code IHederaAccountService} form names the
 * target account explicitly and is how a contract — including one configuring itself — reaches these functions.
 *
 * <p>All five mutating functions produce the same artifact, a {@code CryptoUpdate} carrying staking fields only,
 * so they share a single {@link StakingUpdateCall}. The sixth function, {@code getStakingInfo}, dispatches
 * nothing and is served by {@link GetStakingInfoCall}.
 */
@Singleton
public class StakingTranslator extends AbstractCallTranslator<HasCallAttempt> {

    /** {@code stakeToNode(int64)}, selector {@code 0x5fbd84d5}. */
    public static final SystemContractMethod STAKE_TO_NODE_PROXY = SystemContractMethod.declare(
                    "stakeToNode(int64)", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withCategories(Category.STAKING);

    /** {@code stakeToAccount(address)}, selector {@code 0xa69431fe}. */
    public static final SystemContractMethod STAKE_TO_ACCOUNT_PROXY = SystemContractMethod.declare(
                    "stakeToAccount(address)", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withCategories(Category.STAKING);

    /** {@code unstake()}, selector {@code 0x2def6620}. */
    public static final SystemContractMethod UNSTAKE_PROXY = SystemContractMethod.declare(
                    "unstake()", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withCategories(Category.STAKING);

    /** {@code setDeclineReward(bool)}, selector {@code 0x293d496f}. */
    public static final SystemContractMethod SET_DECLINE_REWARD_PROXY = SystemContractMethod.declare(
                    "setDeclineReward(bool)", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withCategories(Category.STAKING);

    /** {@code stakeToNodeAndDeclineReward(int64,bool)}, selector {@code 0xfad3a941}. */
    public static final SystemContractMethod STAKE_TO_NODE_AND_DECLINE_REWARD_PROXY = SystemContractMethod.declare(
                    "stakeToNodeAndDeclineReward(int64,bool)", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withCategories(Category.STAKING);

    /** {@code stakeToNode(address,int64)}, selector {@code 0x7a852f7c}. */
    public static final SystemContractMethod STAKE_TO_NODE = SystemContractMethod.declare(
                    "stakeToNode(address,int64)", ReturnTypes.INT_64)
            .withCategories(Category.STAKING);

    /** {@code stakeToAccount(address,address)}, selector {@code 0x7563f477}. */
    public static final SystemContractMethod STAKE_TO_ACCOUNT = SystemContractMethod.declare(
                    "stakeToAccount(address,address)", ReturnTypes.INT_64)
            .withCategories(Category.STAKING);

    /** {@code unstake(address)}, selector {@code 0xf2888dbb}. */
    public static final SystemContractMethod UNSTAKE =
            SystemContractMethod.declare("unstake(address)", ReturnTypes.INT_64).withCategories(Category.STAKING);

    /** {@code setDeclineReward(address,bool)}, selector {@code 0xf8afc6b4}. */
    public static final SystemContractMethod SET_DECLINE_REWARD = SystemContractMethod.declare(
                    "setDeclineReward(address,bool)", ReturnTypes.INT_64)
            .withCategories(Category.STAKING);

    /** {@code stakeToNodeAndDeclineReward(address,int64,bool)}, selector {@code 0xd52d84ea}. */
    public static final SystemContractMethod STAKE_TO_NODE_AND_DECLINE_REWARD = SystemContractMethod.declare(
                    "stakeToNodeAndDeclineReward(address,int64,bool)", ReturnTypes.INT_64)
            .withCategories(Category.STAKING);

    /** {@code getStakingInfo()}, selector {@code 0xb40cd21d}. */
    public static final SystemContractMethod GET_STAKING_INFO_PROXY = SystemContractMethod.declare(
                    "getStakingInfo()", ReturnTypes.RESPONSE_CODE_STAKING_INFO)
            .withVia(CallVia.PROXY)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.STAKING);

    /** {@code getStakingInfo(address)}, selector {@code 0xaa4704f3}. */
    public static final SystemContractMethod GET_STAKING_INFO = SystemContractMethod.declare(
                    "getStakingInfo(address)", ReturnTypes.RESPONSE_CODE_STAKING_INFO)
            .withModifier(Modifier.VIEW)
            .withCategories(Category.STAKING);

    private static final SystemContractMethod[] ALL_METHODS = {
        STAKE_TO_NODE_PROXY,
        STAKE_TO_ACCOUNT_PROXY,
        UNSTAKE_PROXY,
        SET_DECLINE_REWARD_PROXY,
        STAKE_TO_NODE_AND_DECLINE_REWARD_PROXY,
        STAKE_TO_NODE,
        STAKE_TO_ACCOUNT,
        UNSTAKE,
        SET_DECLINE_REWARD,
        STAKE_TO_NODE_AND_DECLINE_REWARD,
        GET_STAKING_INFO_PROXY,
        GET_STAKING_INFO
    };

    /**
     * Default constructor for injection.
     *
     * @param systemContractMethodRegistry the registry to declare these methods in
     * @param contractMetrics the metrics collector
     */
    @Inject
    public StakingTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HAS, systemContractMethodRegistry, contractMetrics);

        registerMethods(ALL_METHODS);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt);
        final var stakingEnabled = attempt.configuration()
                .getConfigData(ContractsConfig.class)
                .systemContractAccountServiceStakingEnabled();
        if (!stakingEnabled) {
            return Optional.empty();
        }
        final var method = attempt.isMethod(ALL_METHODS);
        // A facade form names no account, so it only means something when the call was redirected from an
        // account address. Selector matching alone does not distinguish the two (CallVia is metadata, not part
        // of the match), so reject a facade selector sent straight to 0x16a rather than letting it reach
        // HasCallAttempt#redirectAccountId, which throws when there is no redirect.
        if (method.isPresent() && method.get().via() == CallVia.PROXY && !attempt.isRedirect()) {
            return Optional.empty();
        }
        return method;
    }

    @Override
    public Call callFrom(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt);

        // --- IHRC632 facade forms: the target is the account the facade was called on -------------------
        if (attempt.isSelector(STAKE_TO_NODE_PROXY)) {
            final var call = STAKE_TO_NODE_PROXY.decodeCall(attempt.inputBytes());
            return stakeToNode(attempt, facadeTarget(attempt), call.get(0));
        } else if (attempt.isSelector(STAKE_TO_ACCOUNT_PROXY)) {
            final var call = STAKE_TO_ACCOUNT_PROXY.decodeCall(attempt.inputBytes());
            return stakeToAccount(attempt, facadeTarget(attempt), call.get(0));
        } else if (attempt.isSelector(UNSTAKE_PROXY)) {
            return unstake(attempt, facadeTarget(attempt));
        } else if (attempt.isSelector(SET_DECLINE_REWARD_PROXY)) {
            final var call = SET_DECLINE_REWARD_PROXY.decodeCall(attempt.inputBytes());
            return setDeclineReward(attempt, facadeTarget(attempt), call.get(0));
        } else if (attempt.isSelector(STAKE_TO_NODE_AND_DECLINE_REWARD_PROXY)) {
            final var call = STAKE_TO_NODE_AND_DECLINE_REWARD_PROXY.decodeCall(attempt.inputBytes());
            return stakeToNodeAndDeclineReward(attempt, facadeTarget(attempt), call.get(0), call.get(1));
        } else if (attempt.isSelector(GET_STAKING_INFO_PROXY)) {
            return new GetStakingInfoCall(attempt, GET_STAKING_INFO_PROXY, facadeTarget(attempt));
        }

        // --- IHederaAccountService forms: the target is named explicitly --------------------------------
        else if (attempt.isSelector(STAKE_TO_NODE)) {
            final var call = STAKE_TO_NODE.decodeCall(attempt.inputBytes());
            return stakeToNode(attempt, namedTarget(attempt, call.get(0)), call.get(1));
        } else if (attempt.isSelector(STAKE_TO_ACCOUNT)) {
            final var call = STAKE_TO_ACCOUNT.decodeCall(attempt.inputBytes());
            return stakeToAccount(attempt, namedTarget(attempt, call.get(0)), call.get(1));
        } else if (attempt.isSelector(UNSTAKE)) {
            final var call = UNSTAKE.decodeCall(attempt.inputBytes());
            return unstake(attempt, namedTarget(attempt, call.get(0)));
        } else if (attempt.isSelector(SET_DECLINE_REWARD)) {
            final var call = SET_DECLINE_REWARD.decodeCall(attempt.inputBytes());
            return setDeclineReward(attempt, namedTarget(attempt, call.get(0)), call.get(1));
        } else if (attempt.isSelector(STAKE_TO_NODE_AND_DECLINE_REWARD)) {
            final var call = STAKE_TO_NODE_AND_DECLINE_REWARD.decodeCall(attempt.inputBytes());
            return stakeToNodeAndDeclineReward(attempt, namedTarget(attempt, call.get(0)), call.get(1), call.get(2));
        } else if (attempt.isSelector(GET_STAKING_INFO)) {
            final var call = GET_STAKING_INFO.decodeCall(attempt.inputBytes());
            return new GetStakingInfoCall(attempt, GET_STAKING_INFO, namedTarget(attempt, call.get(0)));
        }
        return null;
    }

    // --- Body factories, one per function ---------------------------------------------------------------

    private Call stakeToNode(
            @NonNull final HasCallAttempt attempt, @Nullable final AccountID target, final long nodeId) {
        // Note -1 is legal here: it is the documented spelling of unstake(), and the sentinel the handler
        // already understands. Only stakeToNodeAndDeclineReward rejects negatives.
        return callFor(attempt, target, CryptoUpdateTransactionBody.newBuilder().stakedNodeId(nodeId));
    }

    private Call stakeToAccount(
            @NonNull final HasCallAttempt attempt, @Nullable final AccountID target, @NonNull final Address stakedTo) {
        // The zero address needs no special case: it converts to 0.0.0, which is the HAPI staked_account_id
        // sentinel for "no target", and it converts without consulting the entity id factory, so this is
        // correct on networks that do not run in shard 0 realm 0.
        final var stakedToId = attempt.addressIdConverter().convert(stakedTo);
        return callFor(attempt, target, CryptoUpdateTransactionBody.newBuilder().stakedAccountId(stakedToId));
    }

    private Call unstake(@NonNull final HasCallAttempt attempt, @Nullable final AccountID target) {
        // Of the two ways to spell "no staking target" we take the node-id sentinel: it is a plain scalar
        // needing no shard/realm, and it is what Account#staked_node_id already stores for an unstaked account.
        return callFor(attempt, target, CryptoUpdateTransactionBody.newBuilder().stakedNodeId(SENTINEL_NODE_ID));
    }

    private Call setDeclineReward(
            @NonNull final HasCallAttempt attempt, @Nullable final AccountID target, final boolean decline) {
        return callFor(attempt, target, CryptoUpdateTransactionBody.newBuilder().declineReward(decline));
    }

    private Call stakeToNodeAndDeclineReward(
            @NonNull final HasCallAttempt attempt,
            @Nullable final AccountID target,
            final long nodeId,
            final boolean decline) {
        // Deliberately does not accept the -1 sentinel: a function whose name says "stake to node" is not the
        // way to stop staking. A caller wanting both composes unstake() and setDeclineReward().
        if (nodeId < 0) {
            return new StakingUpdateCall(attempt, INVALID_STAKING_ID);
        }
        return callFor(
                attempt,
                target,
                CryptoUpdateTransactionBody.newBuilder().stakedNodeId(nodeId).declineReward(decline));
    }

    // --- Target resolution ------------------------------------------------------------------------------

    /**
     * The account a facade-form call targets, or null if it cannot be resolved. {@link #identifyMethod} only
     * matches a facade form on a redirect, so the null case here is a redirect whose account does not exist.
     */
    private @Nullable AccountID facadeTarget(@NonNull final HasCallAttempt attempt) {
        return attempt.isRedirect() ? attempt.redirectAccountId() : null;
    }

    /**
     * The account an explicit form names. An address that resolves to no account yields an alias-keyed id, and
     * the dispatched {@code CryptoUpdate} then fails with {@code INVALID_ACCOUNT_ID}, which is the right answer.
     */
    private @NonNull AccountID namedTarget(@NonNull final HasCallAttempt attempt, @NonNull final Address account) {
        return attempt.addressIdConverter().convert(account);
    }

    private Call callFor(
            @NonNull final HasCallAttempt attempt,
            @Nullable final AccountID target,
            @NonNull final CryptoUpdateTransactionBody.Builder body) {
        if (target == null) {
            return new StakingUpdateCall(attempt, INVALID_ACCOUNT_ID);
        }
        return new StakingUpdateCall(
                attempt,
                TransactionBody.newBuilder()
                        .cryptoUpdateAccount(body.accountIDToUpdate(target).build())
                        .build());
    }
}
