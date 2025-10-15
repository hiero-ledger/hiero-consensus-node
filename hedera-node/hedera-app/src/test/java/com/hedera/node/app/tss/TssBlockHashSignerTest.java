// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TssBlockHashSignerTest {
    private static final Bytes FAKE_BLOCK_HASH = Bytes.wrap("FAKE_BLOCK_HASH");
    private static final Bytes FAKE_HINTS_SIGNATURE = CommonUtils.noThrowSha384HashOf(FAKE_BLOCK_HASH);

    @Mock
    private HintsService hintsService;

    @Mock
    private HistoryService historyService;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private HintsContext.Signing signing;

    private TssBlockHashSigner subject;

    enum HintsEnabled {
        YES,
        NO
    }

    enum HistoryEnabled {
        YES,
        NO
    }

    @Test
    void asExpectedWithNothingEnabled() {
        givenSubjectWith(HintsEnabled.NO, HistoryEnabled.NO);

        assertTrue(subject.isReady());

        final var signature = subject.sign(FAKE_BLOCK_HASH).signatureFuture().join();

        assertEquals(FAKE_HINTS_SIGNATURE, signature);
    }

    @Test
    void asExpectedWithJustHintsEnabled() {
        givenSubjectWith(HintsEnabled.YES, HistoryEnabled.NO);

        assertFalse(subject.isReady());
        assertThrows(IllegalStateException.class, () -> subject.sign(FAKE_BLOCK_HASH));
        given(hintsService.isReady()).willReturn(true);
        assertTrue(subject.isReady());
        given(signing.future()).willReturn(CompletableFuture.completedFuture(FAKE_HINTS_SIGNATURE));
        given(hintsService.sign(FAKE_BLOCK_HASH)).willReturn(signing);

        final var signature = subject.sign(FAKE_BLOCK_HASH).signatureFuture().join();

        assertSame(FAKE_HINTS_SIGNATURE, signature);
    }

    @Test
    void asExpectedWithJustHistoryEnabled() {
        givenSubjectWith(HintsEnabled.NO, HistoryEnabled.YES);

        assertFalse(subject.isReady());
        assertThrows(IllegalStateException.class, () -> subject.sign(FAKE_BLOCK_HASH));
        given(historyService.isReady()).willReturn(true);
        assertTrue(subject.isReady());

        final var signature = subject.sign(FAKE_BLOCK_HASH).signatureFuture().join();

        assertEquals(FAKE_HINTS_SIGNATURE, signature);
    }

    @Test
    void asExpectedWithBothEnabled() {
        givenSubjectWith(HintsEnabled.YES, HistoryEnabled.YES);

        assertFalse(subject.isReady());
        assertThrows(IllegalStateException.class, () -> subject.sign(FAKE_BLOCK_HASH));
        given(historyService.isReady()).willReturn(true);
        assertFalse(subject.isReady());
        assertThrows(IllegalStateException.class, () -> subject.sign(FAKE_BLOCK_HASH));
        given(hintsService.isReady()).willReturn(true);
        assertTrue(subject.isReady());
        given(signing.future()).willReturn(CompletableFuture.completedFuture(FAKE_HINTS_SIGNATURE));
        given(hintsService.sign(FAKE_BLOCK_HASH)).willReturn(signing);

        final var signature = subject.sign(FAKE_BLOCK_HASH).signatureFuture().join();

        assertEquals(FAKE_HINTS_SIGNATURE, signature);
    }

    private void givenSubjectWith(
            @NonNull final HintsEnabled hintsEnabled, @NonNull final HistoryEnabled historyEnabled) {
        given(configProvider.getConfiguration())
                .willReturn(new VersionedConfigImpl(configWith(hintsEnabled, historyEnabled), 123));
        subject = new TssBlockHashSigner(hintsService, historyService, configProvider);
    }

    private Configuration configWith(
            @NonNull final HintsEnabled hintsEnabled, @NonNull final HistoryEnabled historyEnabled) {
        return HederaTestConfigBuilder.create()
                .withValue("tss.hintsEnabled", "" + (hintsEnabled == HintsEnabled.YES))
                .withValue("tss.historyEnabled", "" + (historyEnabled == HistoryEnabled.YES))
                .getOrCreateConfig();
    }
}
