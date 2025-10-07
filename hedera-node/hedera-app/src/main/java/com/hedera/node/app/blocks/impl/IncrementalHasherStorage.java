// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import com.hedera.hapi.block.stream.StreamingTreeSnapshot;
import com.hedera.hapi.block.stream.SubMerkleTree;
import com.hedera.pbj.runtime.io.buffer.Bytes;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Storage and reconstruction utility for {@link IncrementalStreamingHasher}.
 */
class IncrementalHasherStorage {
	private static final int BUFFER_SIZE = 4 * 1024; // 4KB buffer for optimal I/O

	static StreamingTreeSnapshot readStreamingSnapshot(@NonNull final String filepath) {
		// todo
		return null;
	}

	/**
	 * todo
	 * @param basepath
	 * @param hashingStates
	 * @param roundNum
	 */
	static void writeStreamingSnapshots(@NonNull final String basepath, Map<SubMerkleTree, List<Bytes>> hashingStates, final long roundNum) {
		Path created;
		try {
			created = Files.createDirectory(Path.of(basepath).resolve(String.valueOf(roundNum)));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		// write each tree
		hashingStates.forEach((type, hasher) -> {
			final var snapshot = StreamingTreeSnapshot.newBuilder().type(type).nodes(hasher).build();
			final Path treePath;
			try {
				treePath = Files.createFile(created.resolve(filenameFor(type)));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(treePath), BUFFER_SIZE)) {
				out.write(StreamingTreeSnapshot.PROTOBUF.toBytes(snapshot).toByteArray());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	static String filenameFor(@NonNull final SubMerkleTree type) {
		return type.protoName();
	}
}
