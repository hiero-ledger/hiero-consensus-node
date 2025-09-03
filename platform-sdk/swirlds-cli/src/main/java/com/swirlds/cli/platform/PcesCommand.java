// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform;

import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.SubcommandOf;
import picocli.CommandLine;

/**
 * A collection of operations on preconsensus event stream files.
 */
@CommandLine.Command(
        name = "pces",
        mixinStandardHelpOptions = true,
        description = "Operations on preconsensus event stream files.")
@SubcommandOf(PlatformCli.class)
public class PcesCommand {}
