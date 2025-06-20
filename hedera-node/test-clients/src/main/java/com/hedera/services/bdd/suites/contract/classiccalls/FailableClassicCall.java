// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Defines a classic HTS call that can (perhaps) fail given for example invalid
 * amounts, account IDs, token IDs, or NFT IDs. See also {@link ClassicFailureMode}.
 *
 * <p>If a call returns that it can experience a failure mode (via
 * {@link FailableClassicCall#hasFailureMode(ClassicFailureMode)}), then the call must
 * failure mode the call can experience, there is a corresponding method
 */
public interface FailableClassicCall {
    /**
     * Returns the call result for the given records.
     *
     * @param records the records to use to construct the call result
     * @return the call result for the given records
     */
    FailableCallResult asCallResult(List<TransactionRecord> records);

    /**
     * Returns the static call result for the given top level status and result.
     *
     * @param topLevelStatus the top level status
     * @param result the result
     * @return the static call result for the given top level status and result
     */
    FailableStaticCallResult asStaticCallResult(ResponseCodeEnum topLevelStatus, ContractFunctionResult result);

    /**
     * Returns the name of the call.
     *
     * @return the name of the call
     */
    String name();

    /**
     * Returns whether this call can experience the given failure mode.
     *
     * @param mode the failure mode to check
     * @return whether this call can experience the given failure mode
     */
    boolean hasFailureMode(@NonNull ClassicFailureMode mode);

    /**
     * Returns the encoded failure value for the given failure mode, using
     * the given spec to resolve inventory ids from their names in
     * {@link ClassicInventory}.
     *
     * @param mode the failure mode to encode
     * @param spec the spec to use to resolve inventory ids
     * @return the encoded call that should experience the given failure mode
     */
    byte[] encodedCall(@NonNull ClassicFailureMode mode, @NonNull HapiSpec spec);

    /**
     * Returns whether this call can be made statically.
     *
     * @return whether this call can be made statically
     */
    boolean staticCallOk();
}
