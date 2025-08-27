// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.netty;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.grpc.impl.MethodBase;
import com.hedera.node.app.grpc.impl.QueryMethod;
import com.hedera.node.app.grpc.impl.TransactionMethod;
import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import com.hedera.node.app.workflows.query.QueryWorkflow;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Convenient builder API for constructing gRPC Service definitions. The {@link GrpcServiceBuilder}
 * is capable of constructing service definitions for {@link Transaction} based calls using the
 * {@link #transaction(String)} method, or {@link Query} based calls using the {@link
 * #query(String)} method.
 *
 * <p>Every gRPC service definition needs to define, per service method definition, the "marshaller"
 * to use for marshalling and unmarshalling binary data sent in the protocol. Usually this is some
 * kind of protobuf parser. In our case, we simply read a byte array from the {@link InputStream}
 * and pass the raw array to the appropriate workflow implementation {@link IngestWorkflow} or
 * {@link QueryWorkflow}, so they can do the protobuf parsing. We do this to segregate the code.
 * This class is <strong>only</strong> responsible for the gRPC call, the workflows are responsible
 * for working with protobuf.
 */
/*@NotThreadSafe*/
final class GrpcServiceBuilder {
    /** Logger */
    private static final Logger logger = LogManager.getLogger(GrpcServiceBuilder.class);

    /**
     * The marshaller to use to read/write byte arrays to/from {@link InputStream}s. This class is thread safe.
     */
    private final DataBufferMarshaller marshaller;

    private final DataBufferMarshaller jumboMarshaller;

    /** The name of the service we are building. For example, the TokenService. */
    private final String serviceName;

    /**
     * The {@link IngestWorkflow} to invoke for transaction methods.
     *
     * <p>This instance is set in the constructor and reused for all transaction and query handlers defined
     * on this service builder.
     */
    private final IngestWorkflow ingestWorkflow;

    /**
     * The {@link QueryWorkflow} to invoke for query methods.
     *
     * <p>This instance is set in the constructor and reused for all transaction and query handlers defined
     * on this service builder.
     */
    private final QueryWorkflow queryWorkflow;

    /**
     * The set of transaction method names that need corresponding service method definitions generated.
     *
     * <p>Initially this set is empty, and is populated by calls to {@link #transaction(String)}. Then,
     * when {@link #build(Metrics, int)} is called, the set is used to create the transaction service method definitions.
     */
    private final Set<String> txMethodNames = new HashSet<>();

    /**
     * The set of query method names that need corresponding service method definitions generated.
     *
     * <p>Initially this set is empty, and is populated by calls to {@link #query(String)}. Then,
     * when {@link #build(Metrics, int)} is called, the set is used to create the query service method definitions.
     */
    private final Set<String> queryMethodNames = new HashSet<>();

    /**
     * Creates a new builder. Typically only a single builder instance is created per service.
     *
     * @param serviceName The name of the service. Cannot be null or blank.
     * @param ingestWorkflow The workflow to use for handling all transaction ingestion API calls
     * @param queryWorkflow The workflow to use for handling all queries
     * @param marshaller The marshaller to use for reading/writing byte arrays to/from InputStreams
     * @param jumboMarshaller The marshaller to use for handling jumbo transactions
     * @throws NullPointerException if any of the parameters are null
     * @throws IllegalArgumentException if the serviceName is blank
     */
    public GrpcServiceBuilder(
            @NonNull final String serviceName,
            @NonNull final IngestWorkflow ingestWorkflow,
            @NonNull final QueryWorkflow queryWorkflow,
            @NonNull final DataBufferMarshaller marshaller,
            @NonNull final DataBufferMarshaller jumboMarshaller) {
        this.ingestWorkflow = requireNonNull(ingestWorkflow);
        this.queryWorkflow = requireNonNull(queryWorkflow);
        this.serviceName = requireNonNull(serviceName);
        if (serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName cannot be blank");
        }
        this.marshaller = requireNonNull(marshaller);
        this.jumboMarshaller = requireNonNull(jumboMarshaller);
    }

    /**
     * Register the creation of a new gRPC method for handling transactions with the given name.
     * This call is idempotent.
     *
     * @param methodName The name of the transaction method. Cannot be null or blank.
     * @return A reference to the builder.
     * @throws NullPointerException if the methodName is null
     * @throws IllegalArgumentException if the methodName is blank
     */
    public @NonNull GrpcServiceBuilder transaction(@NonNull final String methodName) {
        if (requireNonNull(methodName).isBlank()) {
            throw new IllegalArgumentException("The gRPC method name cannot be blank");
        }

        txMethodNames.add(methodName);
        return this;
    }

    /**
     * Register the creation of a new gRPC method for handling queries with the given name. This
     * call is idempotent.
     *
     * @param methodName The name of the query method. Cannot be null or blank.
     * @return A reference to the builder.
     * @throws NullPointerException if the methodName is null
     * @throws IllegalArgumentException if the methodName is blank
     */
    public @NonNull GrpcServiceBuilder query(@NonNull final String methodName) {
        if (requireNonNull(methodName).isBlank()) {
            throw new IllegalArgumentException("The gRPC method name cannot be blank");
        }

        queryMethodNames.add(methodName);
        return this;
    }

    /**
     * Build a grpc {@link ServerServiceDefinition} for each transaction and query method registered with this builder.
     *
     * @param metrics Used for recording metrics for the transaction or query methods
     * @param configProvider Used for obtaining the maximum message size allowed for the gRPC server
     * @return A {@link ServerServiceDefinition} that can be registered with a gRPC server
     */
    @NonNull
    public ServerServiceDefinition build(@NonNull final Metrics metrics, ConfigProvider configProvider) {
        final var jumboTxnConfig = configProvider.getConfiguration().getConfigData(JumboTransactionsConfig.class);
        final var jumboTxnIsEnabled = jumboTxnConfig.isEnabled();
        final var jumboTxnMaxSize = jumboTxnConfig.maxTxnSize();
        final var messageMaxSize = configProvider
                .getConfiguration()
                .getConfigData(HederaConfig.class)
                .transactionMaxBytes();

        final var builder = ServerServiceDefinition.builder(serviceName);
        txMethodNames.forEach(methodName -> {
            logger.debug("Registering gRPC transaction method {}.{}", serviceName, methodName);
            TransactionMethod method;

            if (jumboTxnIsEnabled && jumboTxnConfig.grpcMethodNames().contains(methodName)) {
                // add jumbo transaction methods
                method = new TransactionMethod(serviceName, methodName, ingestWorkflow, metrics, jumboTxnMaxSize);
                addMethod(builder, serviceName, methodName, method, jumboMarshaller);
            } else {
                // add regular transaction methods
                method = new TransactionMethod(serviceName, methodName, ingestWorkflow, metrics, messageMaxSize);
                addMethod(builder, serviceName, methodName, method, marshaller);
            }
        });
        queryMethodNames.forEach(methodName -> {
            logger.debug("Registering gRPC query method {}.{}", serviceName, methodName);
            final var method = new QueryMethod(serviceName, methodName, queryWorkflow, metrics, messageMaxSize);
            addMethod(builder, serviceName, methodName, method, marshaller);
        });
        return builder.build();
    }

    /** Utility method for adding a {@link MethodBase} to the {@link ServerServiceDefinition.Builder}. */
    private void addMethod(
            @NonNull final ServerServiceDefinition.Builder builder,
            @NonNull final String serviceName,
            @NonNull final String methodName,
            @NonNull final MethodBase method,
            @NonNull final DataBufferMarshaller marshallerToUse) {

        requireNonNull(builder);
        requireNonNull(serviceName);
        requireNonNull(methodName);
        requireNonNull(method);

        final var methodDescriptor = MethodDescriptor.<BufferedData, BufferedData>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName(serviceName + "/" + methodName)
                .setRequestMarshaller(marshallerToUse)
                .setResponseMarshaller(marshallerToUse)
                .build();

        builder.addMethod(
                ServerMethodDefinition.create(methodDescriptor, (call, ignored) -> new ListenerImpl(call, method)));
    }

    /**
     * Listens to events coming from the client and invokes the appropriate method on the {@link MethodBase}. Receives
     * response information via {@link StreamObserver} and passes the response to the client.
     *
     * <p>The {@link Listener} interface is used to receive events from the client. Among the possible events are when
     * the client connection is ready and when a message has arrived. We handle both of these events.
     *
     * <p>When a message is sent, we forward it to the {@link MethodBase}. The {@link MethodBase} communicates back by
     * means of the {@link StreamObserver} interface. There are three cases to handle: a response is ready, an error
     * occurred, the response is complete.
     */
    private static final class ListenerImpl extends Listener<BufferedData> implements StreamObserver<BufferedData> {
        private final ServerCall<BufferedData, BufferedData> call;
        private final MethodBase method;

        private ListenerImpl(
                @NonNull final ServerCall<BufferedData, BufferedData> call, @NonNull final MethodBase method) {
            requireNonNull(call);
            requireNonNull(method);
            this.call = call;
            this.method = method;
        }

        // ================================================================================================================
        // Implementation of Listener
        //
        // These methods are callbacks based on events coming from the CLIENT. When the connection is ready, we have
        // to indicate that we're expecting a message. Netty will then call `onMessage` to give us the message.

        @Override
        public void onReady() {
            // As per the javadoc for `onReady`:
            // Because there is a processing delay to deliver this notification, it is possible for concurrent writes
            // to cause isReady() == false within this callback. Handle "spurious" notifications by checking isReady()'s
            // current value instead of assuming it is now true. If isReady() == false the normal expectations apply,
            // so there would be another onReady() callback.
            if (call.isReady()) {
                call.request(1);
            }
        }

        @Override
        public void onMessage(BufferedData requestBuffer) {
            method.invoke(requestBuffer, this);
        }

        // ================================================================================================================
        // Implementation of StreamObserver
        //
        // The StreamObserver is the callback interface for the SERVER to send messages back to the CLIENT. It will be
        // called by the MethodBase.

        @Override
        public void onNext(BufferedData responseBuffer) {
            // Send the response back to the client
            call.sendHeaders(new Metadata());
            call.sendMessage(responseBuffer);
        }

        @Override
        public void onError(Throwable t) {
            // We have encountered an unknown error.
            var status = Status.UNKNOWN;
            if (t instanceof StatusRuntimeException ex) {
                status = ex.getStatus();
            }
            call.close(status, new Metadata());
        }

        @Override
        public void onCompleted() {
            // We're done. Happy day.
            call.close(Status.OK, new Metadata());
        }
    }
}
