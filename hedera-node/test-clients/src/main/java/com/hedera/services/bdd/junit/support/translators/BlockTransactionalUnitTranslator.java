// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.hapi.node.base.HederaFunctionality.ATOMIC_BATCH;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_DELETE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_UPDATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRS_PUBLICATION;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_APPEND;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.FREEZE;
import static com.hedera.hapi.node.base.HederaFunctionality.HINTS_KEY_PUBLICATION;
import static com.hedera.hapi.node.base.HederaFunctionality.HINTS_PARTIAL_SIGNATURE;
import static com.hedera.hapi.node.base.HederaFunctionality.HINTS_PREPROCESSING_VOTE;
import static com.hedera.hapi.node.base.HederaFunctionality.HISTORY_ASSEMBLY_SIGNATURE;
import static com.hedera.hapi.node.base.HederaFunctionality.HISTORY_PROOF_KEY_PUBLICATION;
import static com.hedera.hapi.node.base.HederaFunctionality.HISTORY_PROOF_VOTE;
import static com.hedera.hapi.node.base.HederaFunctionality.NODE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.NODE_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.NODE_STAKE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.NODE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_SIGN;
import static com.hedera.hapi.node.base.HederaFunctionality.SYSTEM_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.SYSTEM_UNDELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ACCOUNT_WIPE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_AIRDROP;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_BURN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CANCEL_AIRDROP;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CLAIM_AIRDROP;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FEE_SCHEDULE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GRANT_KYC_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_PAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_REJECT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_REVOKE_KYC_FROM_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNFREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNPAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UPDATE_NFTS;
import static com.hedera.hapi.node.base.HederaFunctionality.UTIL_PRNG;
import static com.hedera.services.bdd.junit.support.translators.impl.AirdropRemovalTranslator.AIRDROP_REMOVAL_TRANSLATOR;
import static com.hedera.services.bdd.junit.support.translators.impl.NoExplicitSideEffectsTranslator.NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.translators.impl.AtomicBatchTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.ContractCallTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.ContractCreateTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.ContractDeleteTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.ContractUpdateTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.CryptoCreateTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.CryptoTransferTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.CryptoUpdateTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.EthereumTransactionTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.FileCreateTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.FileUpdateTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.NodeCreateTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.ScheduleCreateTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.ScheduleDeleteTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.ScheduleSignTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.SubmitMessageTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.TokenAirdropTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.TokenAssociateTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.TokenBurnTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.TokenCreateTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.TokenDissociateTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.TokenMintTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.TokenUpdateTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.TokenWipeTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.TopicCreateTranslator;
import com.hedera.services.bdd.junit.support.translators.impl.UtilPrngTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionalUnit;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Defines a translator for a {@link BlockTransactionalUnit} into a list of {@link SingleTransactionRecord}s.
 */
public class BlockTransactionalUnitTranslator {
    private static final Logger log = LogManager.getLogger(BlockTransactionalUnitTranslator.class);

    /**
     * The base translator used to create the {@link SingleTransactionRecord}s.
     */
    private final BaseTranslator baseTranslator;
    /**
     * The translators used to translate the block transaction parts for a logical HAPI transaction.
     */
    private final Map<HederaFunctionality, BlockTransactionPartsTranslator> translators =
            new EnumMap<>(HederaFunctionality.class) {
                {
                    put(CONSENSUS_CREATE_TOPIC, new TopicCreateTranslator());
                    put(CONSENSUS_DELETE_TOPIC, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(CONSENSUS_SUBMIT_MESSAGE, new SubmitMessageTranslator());
                    put(CONSENSUS_UPDATE_TOPIC, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(CONTRACT_CALL, new ContractCallTranslator());
                    put(CONTRACT_CREATE, new ContractCreateTranslator());
                    put(CONTRACT_DELETE, new ContractDeleteTranslator());
                    put(CONTRACT_UPDATE, new ContractUpdateTranslator());
                    put(CRYPTO_APPROVE_ALLOWANCE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(CRYPTO_DELETE_ALLOWANCE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(CRYPTO_CREATE, new CryptoCreateTranslator());
                    put(CRYPTO_DELETE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(CRYPTO_TRANSFER, new CryptoTransferTranslator());
                    put(CRYPTO_UPDATE, new CryptoUpdateTranslator());
                    put(ETHEREUM_TRANSACTION, new EthereumTransactionTranslator());
                    put(FILE_CREATE, new FileCreateTranslator());
                    put(FILE_DELETE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(FILE_UPDATE, new FileUpdateTranslator());
                    put(FILE_APPEND, new FileAppendTranslator());
                    put(FREEZE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(NODE_CREATE, new NodeCreateTranslator());
                    put(NODE_DELETE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(NODE_UPDATE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(NODE_STAKE_UPDATE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(SCHEDULE_CREATE, new ScheduleCreateTranslator());
                    put(SCHEDULE_DELETE, new ScheduleDeleteTranslator());
                    put(SCHEDULE_SIGN, new ScheduleSignTranslator());
                    put(SYSTEM_DELETE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(SYSTEM_UNDELETE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(TOKEN_AIRDROP, new TokenAirdropTranslator());
                    put(TOKEN_CLAIM_AIRDROP, AIRDROP_REMOVAL_TRANSLATOR);
                    put(TOKEN_CANCEL_AIRDROP, AIRDROP_REMOVAL_TRANSLATOR);
                    put(TOKEN_FEE_SCHEDULE_UPDATE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(TOKEN_REJECT, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(TOKEN_ACCOUNT_WIPE, new TokenWipeTranslator());
                    put(TOKEN_ASSOCIATE_TO_ACCOUNT, new TokenAssociateTranslator());
                    put(TOKEN_DISSOCIATE_FROM_ACCOUNT, new TokenDissociateTranslator());
                    put(TOKEN_GRANT_KYC_TO_ACCOUNT, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(TOKEN_REVOKE_KYC_FROM_ACCOUNT, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(TOKEN_BURN, new TokenBurnTranslator());
                    put(TOKEN_CREATE, new TokenCreateTranslator());
                    put(TOKEN_DELETE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(TOKEN_FREEZE_ACCOUNT, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(TOKEN_UNFREEZE_ACCOUNT, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(TOKEN_UPDATE_NFTS, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(TOKEN_MINT, new TokenMintTranslator());
                    put(TOKEN_PAUSE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(TOKEN_UNPAUSE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(TOKEN_UPDATE, new TokenUpdateTranslator());
                    put(UTIL_PRNG, new UtilPrngTranslator());
                    put(ATOMIC_BATCH, new AtomicBatchTranslator());
                    put(HINTS_KEY_PUBLICATION, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(CRS_PUBLICATION, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(HINTS_PARTIAL_SIGNATURE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(HINTS_PREPROCESSING_VOTE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(HISTORY_PROOF_KEY_PUBLICATION, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(HISTORY_ASSEMBLY_SIGNATURE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                    put(HISTORY_PROOF_VOTE, NO_EXPLICIT_SIDE_EFFECTS_TRANSLATOR);
                }
            };

    /**
     * Constructs a new {@link BlockTransactionalUnitTranslator}.
     */
    public BlockTransactionalUnitTranslator() {
        baseTranslator = new BaseTranslator();
    }

    /**
     * Scans a block for genesis information and returns true if found.
     * @param block the block to scan
     * @return true if genesis information was found
     */
    public boolean scanBlockForGenesis(@NonNull final Block block) {
        return baseTranslator.scanMaybeGenesisBlock(block);
    }

    /**
     * Translates the given {@link BlockTransactionalUnit} into a list of {@link SingleTransactionRecord}s.
     * @param unit the unit to translate
     * @return the translated records
     */
    public List<SingleTransactionRecord> translate(@NonNull final BlockTransactionalUnit unit) {
        requireNonNull(unit);
        baseTranslator.prepareForUnit(unit);
        final List<TraceData> followingTraces = new LinkedList<>(unit.allTraces());
        final List<StateChange> remainingStateChanges = new LinkedList<>(unit.stateChanges());
        final List<SingleTransactionRecord> translatedRecords = new ArrayList<>();
        List<TraceData> tracesSoFar = null;
        for (final var blockTransactionParts : unit.blockTransactionParts()) {
            final var translator = translators.get(blockTransactionParts.functionality());
            if (translator == null) {
                log.warn("No translator found for functionality {}, skipping", blockTransactionParts.functionality());
            } else {
                if (blockTransactionParts.hasTraces()) {
                    if (tracesSoFar == null) {
                        tracesSoFar = new ArrayList<>();
                    }
                    tracesSoFar.addAll(blockTransactionParts.tracesOrThrow());
                    // Remove the traces that are part of this transaction from the following traces
                    followingTraces.removeAll(blockTransactionParts.tracesOrThrow());
                }
                final var translation = translator.translate(
                        blockTransactionParts, baseTranslator, remainingStateChanges, tracesSoFar, followingTraces);
                translatedRecords.add(translation);
            }
        }
        baseTranslator.finishLastUnit();
        baseTranslator.updateNoncesAfter(unit);
        return translatedRecords;
    }
}
