// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import static com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule.INITIAL_CONTRACT_NONCE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.getAndClearPendingCreationMetadata;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.hasBytecodeSidecarsEnabled;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.proxyUpdaterFor;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.frame.MessageFrame.State.EXCEPTIONAL_HALT;

import com.hedera.hapi.block.stream.trace.ExecutedInitcode;
import com.hedera.hapi.block.stream.trace.InitcodeBookends;
import com.hedera.hapi.streams.ContractBytecode;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.AbstractProxyEvmAccount;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.contractvalidation.ContractValidationRule;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.tracing.OperationTracer;

/**
 * A customization of the Besu {@link ContractCreationProcessor} that replaces the
 * explicit {@code sender.decrementBalance(frame.getValue())} and
 * {@code contract.incrementBalance(frame.getValue())} calls with a single call
 * to the {@link HederaWorldUpdater#tryTransfer(Address, Address, long, boolean)}
 * dispatch method.
 */
public class CustomContractCreationProcessor extends ContractCreationProcessor {
    // By convention, the halt reason should be INSUFFICIENT_GAS when the contract already exists
    private static final Optional<ExceptionalHaltReason> COLLISION_HALT_REASON =
            Optional.of(ExceptionalHaltReason.INSUFFICIENT_GAS);
    private static final Optional<ExceptionalHaltReason> ENTITY_LIMIT_HALT_REASON =
            Optional.of(CustomExceptionalHaltReason.CONTRACT_ENTITY_LIMIT_REACHED);
    private static final Optional<ExceptionalHaltReason> CHILD_RECORDS_LIMIT_HALT_REASON =
            Optional.of(CustomExceptionalHaltReason.INSUFFICIENT_CHILD_RECORDS);

    /**
     * @param evm the EVM to use
     * @param requireCodeDepositToSucceed whether to require the deposit to succeed
     * @param contractValidationRules the rules against which the contract will be validated
     * @param initialContractNonce the initial contract nonce to use for the creation
     */
    public CustomContractCreationProcessor(
            @NonNull final EVM evm,
            final boolean requireCodeDepositToSucceed,
            @NonNull final List<ContractValidationRule> contractValidationRules,
            final long initialContractNonce) {
        super(
                requireNonNull(evm),
                requireCodeDepositToSucceed,
                requireNonNull(contractValidationRules),
                initialContractNonce);
    }

    @Override
    public void start(@NonNull final MessageFrame frame, @NonNull final OperationTracer tracer) {
        final var addressToCreate = frame.getContractAddress();
        final MutableAccount contract;
        try {
            contract = frame.getWorldUpdater().getOrCreate(addressToCreate);
        } catch (final ResourceExhaustedException e) {
            haltOnResourceExhaustion(frame, tracer, e);
            return;
        }

        if (alreadyCreated(contract)) {
            halt(frame, tracer, COLLISION_HALT_REASON);
        } else {
            final var updater = proxyUpdaterFor(frame);
            if (isHollow(contract)) {
                updater.finalizeHollowAccount(addressToCreate, frame.getSenderAddress());
            }
            // A contract creation is never a delegate call, hence the false argument below
            final var maybeReasonToHalt = updater.tryTransfer(
                    frame.getSenderAddress(), addressToCreate, frame.getValue().toLong(), false);
            if (maybeReasonToHalt.isPresent()) {
                // For some reason Besu doesn't trace the creation on a modification exception, but
                // since our tracer maintains an action stack that must stay in sync with the EVM
                // frame stack, we need to trace the failed creation here too
                halt(frame, tracer, maybeReasonToHalt);
            } else {
                contract.setNonce(INITIAL_CONTRACT_NONCE);
                frame.addCreate(addressToCreate);
                frame.setState(MessageFrame.State.CODE_EXECUTING);
            }
        }
    }

    private void haltOnResourceExhaustion(
            @NonNull final MessageFrame frame,
            @NonNull final OperationTracer tracer,
            @NonNull final ResourceExhaustedException e) {
        switch (e.getStatus()) {
            case MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED -> halt(frame, tracer, ENTITY_LIMIT_HALT_REASON);
            case MAX_CHILD_RECORDS_EXCEEDED -> halt(frame, tracer, CHILD_RECORDS_LIMIT_HALT_REASON);
            default -> throw new IllegalStateException("Unexpected creation failure reason", e);
        }
    }

    @Override
    public void codeSuccess(@NonNull final MessageFrame frame, @NonNull final OperationTracer tracer) {
        super.codeSuccess(requireNonNull(frame), requireNonNull(tracer));
        final boolean validationRuleFailed = frame.getState() == EXCEPTIONAL_HALT;
        if (hasBytecodeSidecarsEnabled(frame)) {
            final var recipient = proxyUpdaterFor(frame).getHederaAccount(frame.getRecipientAddress());
            final var recipientId = requireNonNull(recipient).hederaContractId();
            final var pendingCreationMetadata = getAndClearPendingCreationMetadata(frame, recipientId);
            final var bytecode = tuweniToPbjBytes(recipient.getCode());
            final var initcode = pendingCreationMetadata.needsInitcodeExternalized()
                    ? tuweniToPbjBytes(frame.getCode().getBytes())
                    : null;
            // (FUTURE) Remove this sidecar if/else after switching to block stream
            if (validationRuleFailed) {
                if (initcode != null) {
                    final var sidecar =
                            ContractBytecode.newBuilder().initcode(initcode).build();
                    pendingCreationMetadata.streamBuilder().addContractBytecode(sidecar, false);
                }
            } else {
                final var sidecar =
                        ContractBytecode.newBuilder().contractId(recipientId).runtimeBytecode(bytecode);
                if (initcode != null) {
                    sidecar.initcode(initcode);
                }
                pendingCreationMetadata.streamBuilder().addContractBytecode(sidecar.build(), false);
            }
            // Below is a no-op for the RecordStreamBuilder
            if (!validationRuleFailed && initcode != null) {
                final var initcodeBuilder = ExecutedInitcode.newBuilder().contractId(recipientId);
                final int i = indexOf(initcode, bytecode);
                if (i != -1) {
                    final var leftBookend = initcode.slice(0, i);
                    final int rightIndex = i + (int) bytecode.length();
                    final var rightBookend = initcode.slice(rightIndex, (int) initcode.length() - rightIndex);
                    initcodeBuilder.initcodeBookends(new InitcodeBookends(leftBookend, rightBookend));
                } else {
                    initcodeBuilder.explicitInitcode(initcode);
                }
                pendingCreationMetadata.streamBuilder().addInitcode(initcodeBuilder.build());
            }
        }
    }

    private void halt(
            @NonNull final MessageFrame frame,
            @NonNull final OperationTracer tracer,
            @NonNull final Optional<ExceptionalHaltReason> reason) {
        frame.setState(EXCEPTIONAL_HALT);
        frame.setExceptionalHaltReason(reason);
        tracer.traceAccountCreationResult(frame, reason);
        // TODO - should we revert child records here?
    }

    private boolean alreadyCreated(final MutableAccount account) {
        return account.getNonce() > 0 || !account.getCode().isEmpty() || !account.isStorageEmpty();
    }

    private boolean isHollow(@NonNull final MutableAccount account) {
        if (account instanceof final AbstractProxyEvmAccount abstractProxyEvmAccount) {
            return abstractProxyEvmAccount.isHollow();
        }
        throw new IllegalArgumentException("Creation target not a AbstractProxyEvmAccount - " + account);
    }

    /**
     * Returns the first byte offset of {@code needle} inside {@code haystack},
     * or –1 if it is not present.
     * <br>
     * <i>(FUTURE)</i> Replace with {@code Bytes#indexOf(Bytes, Bytes)} when
     * <a href="https://github.com/hashgraph/pbj/pull/503">this</a> PBJ PR is merged.
     */
    private static int indexOf(@NonNull final Bytes haystackBytes, @NonNull final Bytes needleBytes) {
        requireNonNull(haystackBytes);
        requireNonNull(needleBytes);
        final var haystack = haystackBytes.toByteArray();
        final var needle = needleBytes.toByteArray();
        // Empty needle found at index zero in any haystack
        if (needle.length == 0) {
            return 0;
        }
        // Needle doesn't fit into haystack, so it cannot be found
        if (needle.length > haystack.length) {
            return -1;
        }
        final byte firstByte = needle[0];
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            if (haystack[i] != firstByte) {
                continue;
            }
            // SIMD–accelerated block comparison.
            if (Arrays.mismatch(haystack, i, i + needle.length, needle, 0, needle.length) < 0) {
                return i;
            }
        }
        return -1;
    }
}
