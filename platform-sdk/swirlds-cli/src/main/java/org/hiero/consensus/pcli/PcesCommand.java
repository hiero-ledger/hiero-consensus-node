// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import picocli.CommandLine;

/**
 * A collection of operations on preconsensus event stream files.
 */
@CommandLine.Command(
        name = "pces",
        mixinStandardHelpOptions = true,
        description = "Operations on preconsensus event stream files.")
@SubcommandOf(Pcli.class)
public class PcesCommand {}
