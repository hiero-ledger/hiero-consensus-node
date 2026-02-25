// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateCommonDecoder.FAILURE_CUSTOMIZER;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class UpdateTokenCommonTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    protected static final String UPDATE_TOKEN_INFO_STRING = "updateTokenInfo(address,";

    protected final Map<SystemContractMethod, UpdateDecoderFunction> updateMethodsMap = new ConcurrentHashMap<>();

    public UpdateTokenCommonTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        return updateMethodsMap.keySet().stream().filter(attempt::isSelector).findFirst();
    }

    @Override
    public Call callFrom(@NonNull HtsCallAttempt attempt) {
        return new DispatchForResponseCodeHtsCall(
                attempt, nominalBodyFor(attempt), UpdateCommons::gasRequirement, FAILURE_CUSTOMIZER);
    }

    private TransactionBody nominalBodyFor(@NonNull final HtsCallAttempt attempt) {
        return updateMethodsMap.entrySet().stream()
                .filter(entry -> attempt.isSelector(entry.getKey()))
                .map(entry -> entry.getValue().decode(attempt))
                .findFirst()
                .orElse(null);
    }
}
