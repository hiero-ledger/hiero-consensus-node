// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blockamplification;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Shared scaffolding for the block-stream amplification proof-of-concept suite.
 *
 * <p>Each proof-of-concept runs on a one-node {@code @GenesisSubprocessTest} network started with
 * {@code blockStream.streamMode=BLOCKS}, the #27718 state-change deltas on ({@code enableStateChangeDeltas=true},
 * {@code stateChangeDeltaMinSaving=16}), and the #27492/#28153 execution budget enabled with a deliberately small
 * {@code maxBlockExecutionSizeBytes}. The real mainnet throttles (including the HIP-1313 high-volume lanes) are loaded
 * at the start of every spec from {@code testSystemFiles/mainnet-throttles-hv.json}.
 *
 * <p>The budget is an admission gate: the transaction that crosses {@code maxBlockExecutionSizeBytes} is still emitted
 * in full, and every later non-exempt transaction in the same block is rejected with {@code THROTTLED_AT_CONSENSUS}
 * and charged normal consensus throttle fees (see {@code HandleWorkflow#executeSubmittedParent}). These proofs drive a
 * single cheap transaction type until that gate trips, then assert the rejection is observed.
 *
 * <p>Setup transactions are paid by {@code GENESIS} (account {@code 0.0.2}), which is in the throttle-exempt range and
 * therefore never rejected by the budget; the attacker transactions are paid by a normal civilian account.
 */
public final class BlockAmplificationSupport {
    private BlockAmplificationSupport() {}

    /** Real mainnet throttles, including the HIP-1313 high-volume buckets (copied from configuration/mainnet). */
    public static final String MAINNET_THROTTLES_WITH_HV = "testSystemFiles/mainnet-throttles-hv.json";

    /** Civilian attacker account; outside the throttle-exempt range, so its transactions are budget-eligible. */
    public static final String ATTACKER = "amplificationAttacker";
    /** A plain receiver for filler transfers. */
    public static final String RECEIVER = "amplificationReceiver";

    /** The largest a single file-append payload can be under the default {@code transaction.maxBytes=6144}. */
    public static final int APPEND_CHUNK_BYTES = 4096;

    /** A deterministic byte payload of the given size. */
    public static byte[] payload(final int size) {
        final byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ('a' + (i % 26));
        }
        return b;
    }

    /**
     * Wraps the given EVM runtime bytecode in the minimal constructor that returns it as the deployed code
     * (PUSH len; PUSH 0x0c; PUSH 0; CODECOPY; PUSH len; PUSH 0; RETURN, then the runtime).
     */
    public static ByteString initcodeReturning(final byte[] runtime) {
        final int len = runtime.length;
        final byte[] prefix = {0x60, (byte) len, 0x60, 0x0c, 0x60, 0x00, 0x39, 0x60, (byte) len, 0x60, 0x00, (byte) 0xf3
        };
        return ByteString.copyFrom(prefix).concat(ByteString.copyFrom(runtime));
    }

    /** A 32-byte big-endian EVM word holding {@code n}, for use as explicit call parameters. */
    public static byte[] word(final long n) {
        final byte[] b = new byte[32];
        for (int i = 0; i < 8; i++) {
            b[31 - i] = (byte) (n >>> (8 * i));
        }
        return b;
    }

    /**
     * Builds EVM runtime bytecode that performs {@code repeats} unrolled {@code CALL}s to the system contract at
     * {@code precompileAddress}, each passing {@code argsSize} bytes of (zero) memory as input. A non-static {@code CALL}
     * (not {@code STATICCALL}) is used so the callee runs in a mutable frame and externalizes its child record. This is
     * the lever behind the PRNG (0x169) and HTS/HAS/HSS (0x167/0x16a/0x16b) child-record vectors: each such call makes
     * the system contract copy its input into a child record's {@code call_data}.
     *
     * <p>Keep {@code repeats} small enough that the runtime stays under 256 bytes (the minimal deployer uses a one-byte
     * length), i.e. at most ~13 repeats.
     */
    public static byte[] runtimeCallingPrecompile(final int precompileAddress, final int argsSize, final int repeats) {
        final var out = new java.io.ByteArrayOutputStream();
        for (int r = 0; r < repeats; r++) {
            out.write(0x60);
            out.write(0x20); // PUSH1 32   -> retSize
            out.write(0x60);
            out.write(0x00); // PUSH1 0    -> retOffset
            out.write(0x61);
            out.write((argsSize >> 8) & 0xff);
            out.write(argsSize & 0xff); // PUSH2 argsSize
            out.write(0x60);
            out.write(0x00); // PUSH1 0    -> argsOffset
            out.write(0x60);
            out.write(0x00); // PUSH1 0    -> value
            out.write(0x61);
            out.write((precompileAddress >> 8) & 0xff);
            out.write(precompileAddress & 0xff); // PUSH2 address
            out.write(0x5a); // GAS
            out.write(0xf1); // CALL
            out.write(0x50); // POP success flag
        }
        out.write(0x00); // STOP
        return out.toByteArray();
    }

    /**
     * Grows {@code file} to at least {@code targetBytes} using {@code GENESIS}-paid appends. Because {@code GENESIS} is
     * throttle-exempt, these setup appends are never rejected by the execution budget, even though each one re-emits the
     * whole (growing) file to the block stream under #27718.
     */
    public static SpecOperation growFileWithExemptAppends(final String file, final int targetBytes) {
        return withOpContext((spec, opLog) -> {
            final byte[] chunk = payload(APPEND_CHUNK_BYTES);
            int written = 0;
            while (written < targetBytes) {
                // The default signers add the payer plus the file's WACL (saved under the file name), so a
                // GENESIS-paid append is authorized; GENESIS (0.0.2 <= accounts.lastThrottleExempt) is also exempt
                // from the execution budget, so these setup appends always succeed.
                allRunFor(
                        spec,
                        fileAppend(file).content(chunk).payingWith(GENESIS).noLogging());
                written += chunk.length;
            }
            opLog.info("[AMPLIFICATION] grew {} to ~{} bytes with exempt appends", file, written);
        });
    }

    /**
     * Gives {@code owner} a {@code count}-entry crypto allowance list, paid by the throttle-exempt {@code GENESIS}
     * account (so building the list is never rejected by the execution budget) but owned and signed by {@code owner}.
     * The spenders are {@code count} fresh zero-balance accounts named {@code spenderPrefix + index}. Allowances are
     * granted 20 per transaction (the {@code allowances.maxTransactionLimit}).
     */
    public static SpecOperation buildCryptoAllowanceList(
            final String owner, final String spenderPrefix, final int count, final long perAllowance) {
        return withOpContext((spec, opLog) -> {
            for (int i = 0; i < count; i++) {
                allRunFor(
                        spec,
                        cryptoCreate(spenderPrefix + i)
                                .balance(0L)
                                .payingWith(GENESIS)
                                .noLogging());
            }
            for (int start = 0; start < count; start += 20) {
                final var approve = cryptoApproveAllowance()
                        .payingWith(GENESIS)
                        .signedBy(GENESIS, owner)
                        .noLogging();
                for (int i = start; i < Math.min(start + 20, count); i++) {
                    approve.addCryptoAllowance(owner, spenderPrefix + i, perAllowance);
                }
                allRunFor(spec, approve);
            }
            opLog.info("[AMPLIFICATION] built a {}-entry crypto allowance list on {}", count, owner);
        });
    }

    /**
     * Submits {@code attempts} copies of the given attacker transaction and asserts the execution budget rejects at
     * least one of them with {@code THROTTLED_AT_CONSENSUS} while at least one earlier copy still succeeds (the copy
     * that crosses the budget is emitted in full). Each copy tolerates either status, so the proof is robust to exactly
     * where in the real-time block the gate happens to trip.
     *
     * @param label a short label used for the {@code via} names and the summary log line
     * @param attempts how many copies of the attacker transaction to submit
     * @param attackTxn a supplier of a fresh attacker transaction (already carrying its payer and any high-volume flag)
     */
    public static SpecOperation floodAssertingBudgetRejection(
            final String label,
            final int attempts,
            final Supplier<HapiTxnOp<?>> attackTxn,
            final ResponseCodeEnum... alsoAllowedWhenExecuted) {
        return floodAssertingBudgetRejection(label, attempts, i -> attackTxn.get(), alsoAllowedWhenExecuted);
    }

    /**
     * Index-aware variant of {@link #floodAssertingBudgetRejection(String, int, Supplier, ResponseCodeEnum...)} for
     * attacker transactions that must differ per attempt (e.g. a {@code ScheduleCreate}, which must name a distinct
     * schedule each time).
     */
    public static SpecOperation floodAssertingBudgetRejection(
            final String label,
            final int attempts,
            final IntFunction<HapiTxnOp<?>> attackTxn,
            final ResponseCodeEnum... alsoAllowedWhenExecuted) {
        return withOpContext((spec, opLog) -> {
            final Set<ResponseCodeEnum> allowed = EnumSet.of(SUCCESS, THROTTLED_AT_CONSENSUS);
            allowed.addAll(List.of(alsoAllowedWhenExecuted));
            final ResponseCodeEnum[] allowedArray = allowed.toArray(new ResponseCodeEnum[0]);
            final List<String> vias = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                final String via = label + "-" + i;
                vias.add(via);
                final HapiTxnOp<?> op = attackTxn.apply(i);
                op.via(via);
                op.hasKnownStatusFrom(allowedArray);
                allRunFor(spec, op);
            }
            int executed = 0;
            int throttled = 0;
            for (final String via : vias) {
                final AtomicReference<ResponseCodeEnum> seen = new AtomicReference<>();
                allRunFor(
                        spec,
                        getTxnRecord(via)
                                .noLogging()
                                .exposingTo(
                                        record -> seen.set(record.getReceipt().getStatus())));
                if (seen.get() == THROTTLED_AT_CONSENSUS) {
                    throttled++;
                } else if (seen.get() != null) {
                    executed++;
                }
            }
            opLog.info(
                    "[AMPLIFICATION] {}: of {} attacker transactions, {} executed (at least one crossed the budget) "
                            + "and {} were rejected THROTTLED_AT_CONSENSUS by the execution budget",
                    label,
                    attempts,
                    executed,
                    throttled);
            assertTrue(
                    throttled > 0,
                    "Expected the execution budget to reject at least one attacker transaction with "
                            + "THROTTLED_AT_CONSENSUS, but saw " + executed + " executed and 0 rejections across "
                            + attempts + " attempts for " + label);
        });
    }
}
