// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.SystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public abstract class CreateCommonTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    // Map with classic create methods and their respective decoders
    protected ConcurrentHashMap<SystemContractMethod, CreateDecoderFunction> createMethodsMap =
            new ConcurrentHashMap<>();

    protected CreateCommonTranslator(
            @NonNull SystemContract systemContractKind,
            @NonNull SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull ContractMetrics contractMetrics) {
        super(systemContractKind, systemContractMethodRegistry, contractMetrics);
    }

    @NonNull
    @Override
    public Optional<SystemContractMethod> identifyMethod(@NonNull HtsCallAttempt attempt) {
        return createMethodsMap.keySet().stream()
                .filter(method -> attempt.isMethod(method).isPresent())
                .findFirst();
    }

    @Override
    public Call callFrom(@NonNull HtsCallAttempt attempt) {
        return new ClassicCreatesCall(
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                nominalBodyFor(attempt),
                attempt.defaultVerificationStrategy(),
                attempt.senderId());
    }

    private @Nullable TransactionBody nominalBodyFor(@NonNull final HtsCallAttempt attempt) {
        final var inputBytes = attempt.inputBytes();
        final var senderId = attempt.senderId();
        final var nativeOperations = attempt.nativeOperations();
        final var addressIdConverter = attempt.addressIdConverter();

        return createMethodsMap.entrySet().stream()
                .filter(entry -> attempt.isSelector(entry.getKey()))
                .map(entry -> entry.getValue().decode(inputBytes, senderId, nativeOperations, addressIdConverter))
                .findFirst()
                .orElse(null);
    }
}
