// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;
import java.time.Duration;

/**
 * Configuration for staging the block(s) around a self/catastrophic ISS to local disk for developer triage.
 *
 * <p>The node does not upload anything itself: it writes the captured artifacts into {@link #issBlockDir}, and the
 * deployment's existing stream uploader (a separate process that watches that directory) ships them to the bucket. This
 * keeps bucket credentials out of the JVM and off the catastrophic-failure path.
 *
 * <p>There are two independent capture paths, each gated by its own flag so an operator can run either, both, or
 * neither: {@link #issBlockUploadEnabled} stages the exact ISS-round block (located at detection time); the flushed
 * open/pending blocks captured at catastrophic failure are staged when {@link #triageUploadEnabled} is set.
 *
 * <p>In {@code blockStream.writerMode=GRPC} the ISS-round block lives only in the in-memory block buffer, so its
 * detection-time capture is best-effort: it is staged if still buffered (a block node does not acknowledge the ISS
 * block, and unacknowledged blocks are not pruned, so it is normally still present), otherwise a small pointer
 * {@code .txt} — with the data needed to find the block on the block node — is staged instead.
 *
 * <p><b>DEV-OPS RESPONSIBILITY — required before this feature does anything in a real deployment.</b> The two items
 * below are deployment (NMT / compose) actions, NOT code changes; until both are done the node stages the block to
 * local disk but nothing ships it:
 * <ol>
 *   <li><b>TODO(devops): make {@link #issBlockDir} a host bind mount.</b> mainnet/testnet/previewnet persist via bind
 *   mounts; on an unmounted path the staged block is lost when the consensus container dies.</li>
 *   <li><b>TODO(devops): provision an uploader instance for {@link #issBlockDir}.</b> Point a mirror.py uploader at it
 *   with its own bucket + key — a private bucket (e.g. the backups bucket), ideally a create-only key. It must ship
 *   <b>every</b> staged extension — {@code .gz} blocks, {@code .pnd.json} proof sidecars, and the {@code .txt} pointer
 *   fallback — not only {@code .gz}, or the sidecars/pointer are staged locally but never reach the bucket.</li>
 * </ol>
 * The exact host dir and bucket/key are owned by DevOps and are intentionally not pinned here.
 *
 * @param issBlockUploadEnabled whether the detection-time ISS-block capture (staged into {@code issBlockDir}) is active
 * @param triageUploadEnabled whether the catastrophic-failure flushed-set capture (staged into {@code issBlockDir}) is
 * active
 * @param issBlockDir the node-local directory the captured artifacts are staged into; <b>must be a bind-mounted host
 * path</b> (see the TODO above) so the deployment's uploader can ship them. Artifacts are written under a
 * {@code block-<account>/<timestamp>} subdir per incident
 * @param precedingBlocks how many blocks immediately before the ISS block to also capture (0 = exactly the ISS-round
 * block); best-effort and clamped to what is actually retained
 * @param captureTimeout how long the detection path waits for the ISS-round block to become a durable on-disk artifact
 * (it may still be the open, in-progress block at detection); once it elapses the capture is abandoned
 */
@ConfigData("failureBlockUpload")
public record FailureBlockUploadConfig(
        @ConfigProperty(defaultValue = "false") @NodeProperty
        boolean issBlockUploadEnabled,

        @ConfigProperty(defaultValue = "false") @NodeProperty
        boolean triageUploadEnabled,

        // TODO(devops): this must be a host BIND MOUNT and have a mirror.py uploader instance provisioned against it
        // (with a private bucket + key) before this feature ships anything. Deployment action, not a code change; see
        // the DEV-OPS RESPONSIBILITY note in the class Javadoc.
        @ConfigProperty(defaultValue = "/opt/hgcapp/issBlocks") @NodeProperty
        String issBlockDir,

        @ConfigProperty(defaultValue = "0") @Min(0) @NodeProperty
        int precedingBlocks,

        @ConfigProperty(defaultValue = "30s") @NodeProperty Duration captureTimeout) {}
