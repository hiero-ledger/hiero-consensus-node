// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.util.HapiUtils.functionOf;
import static com.hedera.node.app.workflows.standalone.TransactionExecutors.TRANSACTION_EXECUTORS;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.app.workflows.standalone.TransactionExecutors;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.state.State;
import org.hiero.base.exceptions.NotImplementedException;
import org.hiero.hapi.fees.FeeResult;

public class StandaloneFeeCalculatorImpl implements StandaloneFeeCalculator {

    private final SimpleFeeCalculator calc;

    public StandaloneFeeCalculatorImpl(
            State state, TransactionExecutors.Properties properties, EntityIdFactory entityIdFactory) {
        // load a new executor component
        final var executor = TRANSACTION_EXECUTORS.newExecutorComponent(
                properties.state(),
                properties.appProperties(),
                properties.customTracerBinding(),
                properties.customOps(),
                entityIdFactory);
        // init
        executor.stateNetworkInfo().initFrom(state);
        executor.initializer().initialize(state, StreamMode.BOTH);

        // return the calculator
        this.calc = executor.feeManager().getSimpleFeeCalculator();
    }

    @Override
    public FeeResult calculateIntrinsic(Transaction transaction) throws ParseException {
        final var context = new StandaloneFeeContextImpl(transaction);
        return calc.calculateTxFee(context.body(), context);
    }

    @Override
    public FeeResult calculateStateful(Transaction transaction) {
        throw new NotImplementedException();
    }

    private class StandaloneFeeContextImpl implements SimpleFeeContext {

        private final int numTxnSignatures;
        private final TransactionBody body;
        private final Transaction transaction;

        public StandaloneFeeContextImpl(final Transaction transaction) throws ParseException {
            this.transaction = transaction;
            if (transaction.hasBody()) {
                this.body = transaction.bodyOrThrow();
                numTxnSignatures =
                        transaction.sigMapOrElse(SignatureMap.DEFAULT).sigPair().size();
            } else {
                final var signedTransaction = SignedTransaction.PROTOBUF.parse(
                        BufferedData.wrap(transaction.signedTransactionBytes().toByteArray()));
                this.body = TransactionBody.PROTOBUF.parse(signedTransaction.bodyBytes());
                numTxnSignatures = signedTransaction
                        .sigMapOrElse(SignatureMap.DEFAULT)
                        .sigPair()
                        .size();
            }
        }

        @Override
        public int numTxnSignatures() {
            return this.numTxnSignatures;
        }

        @Override
        public int numTxnBytes() {
            return Transaction.PROTOBUF.measureRecord(transaction);
        }

        @Override
        public HederaFunctionality functionality() {
            try {
                return functionOf(body);
            } catch (com.hedera.hapi.util.UnknownHederaFunctionality e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public FeeContext feeContext() {
            return null;
        }

        @Override
        public QueryContext queryContext() {
            return null;
        }

        public TransactionBody body() {
            return this.body;
        }
    }
}
