/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#SYSTEM_UNDELETE}.
 * <p>
 * This transaction type has been deprecated. Because protobufs promise backwards compatibility,
 * we cannot remove it. However, it should not be used.
 */
@Singleton
public class ContractSystemUndeleteHandler implements TransactionHandler {
    /**
     * Default constructor for injection.
     */
    @Inject
    public ContractSystemUndeleteHandler() {
        // Exists for injection
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        // this will never actually get called
        // because pureChecks will always throw
        throw new PreCheckException(NOT_SUPPORTED);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        throw new PreCheckException(NOT_SUPPORTED);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        // this will never actually get called
        // because pureChecks will always throw
        throw new HandleException(NOT_SUPPORTED);
    }

    @NonNull
    @Override
    public Fees calculateFees(final FeeContext feeContext) {
        return Fees.FREE;
    }
}
