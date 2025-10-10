// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static com.hedera.hapi.streams.CallOperationType.OP_UNKNOWN;
import static com.hedera.hapi.streams.ContractActionType.NO_ACTION;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.hederaIdNumOfContractIn;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.app.service.contract.impl.utils.OpcodeUtils.asCallOperationType;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Helper class for pretty-printing, validating, and creating synthetic {@link ContractAction}s.
 */
public class ActionsHelper {
    private static final Bytes MISSING_ADDRESS_ERROR = Bytes.wrap("INVALID_SOLIDITY_ADDRESS".getBytes(UTF_8));

    /**
     * Given a frame, returns a {@link ContractAction} that represents it as a call to a missing address.
     *
     * @param frame the frame to represent
     * @return the {@link ContractAction} representing the frame as a call to a missing address
     */
    public ContractAction createSynthActionForMissingAddressIn(
            @NonNull final MessageFrame frame, @NonNull final Address targetAddress) {
        return ContractAction.newBuilder()
                .callType(ContractActionType.CALL)
                .gas(frame.getRemainingGas())
                .callDepth(frame.getDepth() + 1)
                .callingContract(contractIdWith(frame, hederaIdNumOfContractIn(frame)))
                .targetedAddress(tuweniToPbjBytes(targetAddress))
                .error(MISSING_ADDRESS_ERROR)
                .callOperationType(
                        asCallOperationType(frame.getCurrentOperation().getOpcode()))
                .build();
    }

    /**
     * Given a {@link ContractAction}, returns whether it is valid.
     *
     * @param action the action to validate
     * @return whether the given action is valid
     */
    public boolean isValid(@NonNull final ContractAction action) {
        var ok = true;
        ok &= null != action.callType() && NO_ACTION != action.callType();
        ok &= 1 == countNonNulls(action.callingAccount(), action.callingContract());
        ok &= action.input().length() > 0;
        ok &= 1 >= countNonNulls(action.recipientAccount(), action.recipientContract(), action.targetedAddress());
        ok &= 1 == countNonNulls(action.output(), action.revertReason(), action.error());
        ok &= null != action.callOperationType() && OP_UNKNOWN != action.callOperationType();
        return ok;
    }

    /**
     * Returns a pretty-printed string representation of the given {@link ContractAction}.
     *
     * @param action the action to pretty-print
     * @return the pretty-printed string representation of the given {@link ContractAction}
     */
    public String prettyPrint(@NonNull final ContractAction action) {
        final var sb = new StringBuilder();

        Function<Object, String> fobj = o -> null != o ? o.toString() : "<null>";
        Function<byte[], String> fbytes = b -> null != b
                ? (0 != b.length ? org.apache.tuweni.bytes.Bytes.wrap(b).toHexString() : "<empty>")
                : "<null>";
        BiConsumer<String, Object> ff =
                (n, o) -> sb.append("%s: %s, ".formatted(n, o instanceof byte[] b ? fbytes.apply(b) : fobj.apply(o)));

        sb.append("SolidityAction(");
        ff.accept("callType", action.callType());
        ff.accept("callOperationType", action.callOperationType());
        ff.accept("value", action.value());
        ff.accept("gas", action.gas());
        ff.accept("gasUsed", action.gasUsed());
        ff.accept("callDepth", action.callDepth());
        ff.accept("callingAccount", action.callingAccount());
        ff.accept("callingContract", action.callingContract());
        ff.accept("recipientAccount", action.recipientAccount());
        ff.accept("recipientContract", action.recipientContract());
        ff.accept("invalidSolidityAddress (aka targetedAddress)", action.targetedAddress());
        ff.accept("input", action.input());
        ff.accept("output", action.output());
        ff.accept("revertReason", action.revertReason());
        ff.accept("error", action.error());
        sb.setLength(sb.length() - 2);
        sb.append(")");
        return sb.toString();
    }

    private ContractID contractIdWith(@NonNull final MessageFrame frame, final long num) {
        return entityIdFactory(frame).newContractId(num);
    }

    private static int countNonNulls(@NonNull final Object... objs) {
        var count = 0;
        for (var obj : objs) {
            if (null != obj) {
                count++;
            }
        }
        return count;
    }
}
