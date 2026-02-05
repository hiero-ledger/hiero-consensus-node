// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.node.app.workflows.standalone.TransactionExecutors.TRANSACTION_EXECUTORS;

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
        StandaloneFeeContextImpl context = new StandaloneFeeContextImpl();
        final SignedTransaction signedTransaction = SignedTransaction.PROTOBUF.parse(
                BufferedData.wrap(transaction.signedTransactionBytes().toByteArray()));
        if (signedTransaction.hasSigMap()) {
            final var sigMap = signedTransaction.sigMapOrThrow();
            context.setNumTxnSignatures(sigMap.sigPair().size());
        } else {
            context.setNumTxnSignatures(0);
        }
        if (transaction.hasBody()) {
            return calc.calculateTxFee(transaction.bodyOrThrow(), context);
        } else {
            final TransactionBody transactionBody = TransactionBody.PROTOBUF.parse(
                    BufferedData.wrap(signedTransaction.bodyBytes().toByteArray()));
            return calc.calculateTxFee(transactionBody, context);
        }
    }

    @Override
    public FeeResult calculateStateful(Transaction transaction) throws ParseException {
        throw new NotImplementedException();
    }

    private class StandaloneFeeContextImpl implements SimpleFeeContext {

        private int _numTxnSignatures;

        public StandaloneFeeContextImpl() {
            this._numTxnSignatures = 0;
        }

        @Override
        public int numTxnSignatures() {
            return this._numTxnSignatures;
        }

        @Override
        public int numTxnBytes() {
            return 0;
        }

        @Override
        public FeeContext feeContext() {
            return null;
        }

        @Override
        public QueryContext queryContext() {
            return null;
        }

        public void setNumTxnSignatures(int sigcount) {
            this._numTxnSignatures = sigcount;
        }
    }
}
