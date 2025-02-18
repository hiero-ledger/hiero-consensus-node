/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import java.util.HashMap;
import java.util.Optional;

public abstract class CreateCommonTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    // Map with classic create methods and their respective decoders
    protected HashMap<SystemContractMethod, CreateDecoderFunction> createMethodsMap = new HashMap<>();

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
