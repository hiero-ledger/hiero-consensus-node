// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.files;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.services.bdd.spec.HapiSpec;
import org.junit.jupiter.api.Test;

class SysFileUploadFailureSummaryTest {

    /** Stand-in for {@code HapiTxnPrecheckStateException}, which lives in a non-exported test-clients package. */
    private static final class HapiTxnPrecheckStateException extends IllegalStateException {
        HapiTxnPrecheckStateException(final String msg) {
            super(msg);
        }
    }

    /** Pathological throwable whose cause is itself; exercises the self-cycle guard in summarizeCauseChain. */
    private static final class SelfReferencingException extends RuntimeException {
        SelfReferencingException(final String message) {
            super(message);
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    @Test
    void describeFailureReturnsEmptyForNullCause() {
        assertThat(SysFileUploadCommand.describeFailure(null)).isEmpty();
    }

    @Test
    void failureWarningOmitsSeparatorWhenCauseIsNull() {
        assertThat(SysFileUploadCommand.failureWarning(null)).isEqualTo("FAILED Uploading requested system files");
    }

    @Test
    void failureWarningAppendsDescribedCause() {
        final var precheck = new HapiTxnPrecheckStateException(
                "Wrong precheck status for FileAppend in 'UploadSystemFile-150'! Expected OK, actual TRANSACTION_OVERSIZE");
        final var failure = new HapiSpec.Failure(precheck, "Unhandled exception executing 'UploadSystemFile-150'");

        assertThat(SysFileUploadCommand.failureWarning(failure))
                .startsWith("FAILED Uploading requested system files - ")
                .contains("TRANSACTION_OVERSIZE")
                .contains("HapiTxnPrecheckStateException");
    }

    @Test
    void describeFailureSurfacesPrecheckStatusFromWrappedChain() {
        // Mirrors what HapiSpec.exec() captures when fileAppend's precheck rejects with TRANSACTION_OVERSIZE:
        //   updateSpecialFile wraps in IllegalStateException, handleExec wraps in IllegalStateException,
        //   innermost is the HapiTxnPrecheckStateException carrying the actual response code.
        final var precheck = new HapiTxnPrecheckStateException(
                "Wrong precheck status for FileAppend in 'UploadSystemFile-150'! Expected OK, actual TRANSACTION_OVERSIZE");
        final var handleExecWrap = new IllegalStateException(precheck);
        final var updateSpecialFileWrap = new IllegalStateException(handleExecWrap);
        final var failure =
                new HapiSpec.Failure(updateSpecialFileWrap, "Unhandled exception executing 'UploadSystemFile-150'");

        final var description = SysFileUploadCommand.describeFailure(failure);

        assertThat(description)
                .contains("TRANSACTION_OVERSIZE")
                .contains("HapiTxnPrecheckStateException")
                .startsWith(" - ");
    }

    @Test
    void summarizeCauseChainPicksDeepestNonBlankMessage() {
        final var deepest = new IllegalStateException("deepest message");
        final var middle = new RuntimeException(deepest);
        final var outer = new IllegalStateException(middle);

        assertThat(SysFileUploadCommand.summarizeCauseChain(outer)).isEqualTo("IllegalStateException: deepest message");
    }

    @Test
    void summarizeCauseChainHandlesSingleThrowable() {
        final var only = new IllegalStateException("only one");
        assertThat(SysFileUploadCommand.summarizeCauseChain(only)).isEqualTo("IllegalStateException: only one");
    }

    @Test
    void summarizeCauseChainHandlesThrowableWithoutMessage() {
        final var noMessage = new RuntimeException();
        assertThat(SysFileUploadCommand.summarizeCauseChain(noMessage)).isEqualTo("RuntimeException");
    }

    @Test
    void summarizeCauseChainHandlesNull() {
        assertThat(SysFileUploadCommand.summarizeCauseChain(null)).isEmpty();
    }

    @Test
    void summarizeCauseChainBreaksOnSelfReferentialCause() {
        final var selfCycle = new SelfReferencingException("self");
        assertThat(SysFileUploadCommand.summarizeCauseChain(selfCycle))
                .isEqualTo("SelfReferencingException: self");
    }

    @Test
    void summarizeCauseChainStopsAtDepthLimit() {
        // The walker bails out after 8 hops; chains longer than that should not reveal the deepest message.
        Throwable t = new IllegalStateException("deepest");
        for (int i = 1; i <= 10; i++) {
            t = new RuntimeException("level-" + i, t);
        }
        assertThat(SysFileUploadCommand.summarizeCauseChain(t)).doesNotContain("deepest");
    }
}
