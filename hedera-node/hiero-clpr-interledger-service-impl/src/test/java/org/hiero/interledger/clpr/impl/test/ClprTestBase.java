package org.hiero.interledger.clpr.impl.test;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import org.hiero.hapi.interledger.state.clpr.ClprEndpoint;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.ReadableClprLedgerConfigurationStore;
import org.hiero.interledger.clpr.WritableClprLedgerConfigurationStore;
import org.hiero.interledger.clpr.impl.ReadableClprLedgerConfigurationStoreImpl;
import org.hiero.interledger.clpr.impl.WritableClprLedgerConfigurationStoreImpl;
import org.mockito.Mock;

import java.time.Instant;
import java.util.*;

import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_LEDGER_CONFIGURATION_KEY;

public class ClprTestBase {

    //data instances
    final protected byte[] rawLocalLedgerId = "localLedgerId".getBytes();
    final protected byte[] rawRemoteLedgerId = "remoteLedgerId".getBytes();
    protected ClprLedgerId localClprLedgerId;
    protected ClprLedgerConfiguration localClprConfig;
    protected ClprLedgerId remoteClprLedgerId;
    protected ClprLedgerConfiguration remoteClprConfig;


    //states declarations
    protected Map<ClprLedgerId, ClprLedgerConfiguration> configurationMap;
    protected MapWritableKVState<ClprLedgerId, ClprLedgerConfiguration> writableLedgerConfiguration;
    protected MapReadableKVState<ClprLedgerId, ClprLedgerConfiguration> readableLedgerConfiguration;
    protected Map<String, WritableKVState<?, ?>> writableStatesMap;
    protected ReadableStates states;
    protected WritableStates clprStates;

    //stores declarations
    protected ReadableClprLedgerConfigurationStore readableLedgerConfigStore;
    protected WritableClprLedgerConfigurationStore writableLedgerConfigStore;
    @Mock(strictness = Mock.Strictness.LENIENT)
    protected ReadableHistoryStore readableHistoryStore;

    //factory declarations
    protected ReadableStoreFactory mockStoreFactory;

    protected void setupStates() {
        configurationMap =  new HashMap<>(0);
        writableLedgerConfiguration = new MapWritableKVState<>(CLPR_LEDGER_CONFIGURATION_KEY, configurationMap);
        readableLedgerConfiguration = new MapReadableKVState<>(CLPR_LEDGER_CONFIGURATION_KEY, configurationMap);
        writableStatesMap = new TreeMap<>();
        writableStatesMap.put(CLPR_LEDGER_CONFIGURATION_KEY, writableLedgerConfiguration);
        clprStates = new MapWritableStates(writableStatesMap);
        states = new MapReadableStates(writableStatesMap);
        readableLedgerConfigStore = new ReadableClprLedgerConfigurationStoreImpl(states);
        writableLedgerConfigStore = new WritableClprLedgerConfigurationStoreImpl(clprStates);
    }

    private void setupScenario() {
        localClprLedgerId = ClprLedgerId.newBuilder().ledgerId(Bytes.wrap(rawLocalLedgerId)).build();
        localClprConfig = ClprLedgerConfiguration.newBuilder()
                .ledgerId(localClprLedgerId)
                .endpoints(List.of(
                        ClprEndpoint.newBuilder().signingCertificate(Bytes.EMPTY).build(),
                        ClprEndpoint.newBuilder().signingCertificate(Bytes.EMPTY).build()
                ))
                .timestamp(Timestamp.newBuilder().seconds(System.currentTimeMillis()/1000).build())
                .build();
        remoteClprLedgerId = ClprLedgerId.newBuilder().ledgerId(Bytes.wrap(rawRemoteLedgerId)).build();
        remoteClprConfig = ClprLedgerConfiguration.newBuilder()
                .ledgerId(remoteClprLedgerId)
                .endpoints(List.of(
                        ClprEndpoint.newBuilder().signingCertificate(Bytes.EMPTY).build(),
                        ClprEndpoint.newBuilder().signingCertificate(Bytes.EMPTY).build()
                ))
                .timestamp(Timestamp.newBuilder().seconds(System.currentTimeMillis()/1000).build())
                .build();
        configurationMap.put(localClprLedgerId, localClprConfig);
        configurationMap.put(remoteClprLedgerId, remoteClprConfig);
    }

    protected void setupBase() {
        setupStates();
        setupScenario();
    }



}
