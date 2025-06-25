// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.files;

import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.yahcli.config.ConfigManager;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.SysFileUploadSuite;
import com.hedera.services.yahcli.util.ParseUtils;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import picocli.CommandLine;

@CommandLine.Command(
        name = "upload",
        subcommands = {picocli.CommandLine.HelpCommand.class},
        description = "Uploads a system file")
public class SysFileUploadCommand implements Callable<Integer> {
    private static final int DEFAULT_BYTES_PER_APPEND = TxnUtils.BYTES_4K;
    private static final int DEFAULT_APPENDS_PER_BURST = 256;

    public static AtomicReference<String> activeSrcDir = new AtomicReference<>();

    @CommandLine.ParentCommand
    private SysFilesCommand sysFilesCommand;

    @CommandLine.Option(
            names = {"-s", "--source-dir"},
            paramLabel = "source directory",
            defaultValue = "{network}/sysfiles/")
    private String srcDir;

    @CommandLine.Option(
            names = {"--dry-run"},
            description = "only write the serialized form of the system file to disk, do not send a" + " FileUpdate")
    private boolean dryRun;

    @CommandLine.Option(
            names = {"--bytes-per-append"},
            description = "number of bytes to add in each append to a special file (default "
                    + DEFAULT_BYTES_PER_APPEND
                    + ")")
    private Integer bytesPerAppend;

    @CommandLine.Option(
            names = {"--appends-per-burst"},
            description = "number of appends to \"burst\" when uploading a special file (default "
                    + DEFAULT_APPENDS_PER_BURST
                    + ")")
    private Integer appendsPerBurst;

    @CommandLine.Option(
            names = {"--restart-from-failure"},
            description = "try to only append missing content")
    private Boolean restartFromFailure;

    @CommandLine.Parameters(
            arity = "1",
            paramLabel = "<sysfile>",
            description = "one of "
                    + "{ address-book, node-details, fees, rates, props, "
                    + "permissions, throttles, software-zip, telemetry-zip } (or "
                    + "{ 101, 102, 111, 112, 121, 122, 123, 150, 159 })")
    private String sysFile;

    @Override
    public Integer call() throws Exception {
        var config = ConfigUtils.configFrom(sysFilesCommand.getYahcli());
        srcDir = SysFilesCommand.resolvedDir(srcDir, config);
        activeSrcDir.set(srcDir);

        if (isSpecialFile(config)) {
            if (bytesPerAppend == null) {
                bytesPerAppend = TxnUtils.BYTES_4K;
            }
            if (appendsPerBurst == null) {
                appendsPerBurst = DEFAULT_APPENDS_PER_BURST;
            }
            if (restartFromFailure == null) {
                restartFromFailure = Boolean.FALSE;
            }
        } else {
            if (bytesPerAppend != null) {
                throw new CommandLine.ParameterException(
                        sysFilesCommand.getYahcli().getSpec().commandLine(),
                        "Option 'bytesPerAppend' only makes sense for a special file");
            }
            if (appendsPerBurst != null) {
                throw new CommandLine.ParameterException(
                        sysFilesCommand.getYahcli().getSpec().commandLine(),
                        "Option 'appendsPerBurst' only makes sense for a special file");
            }
            if (restartFromFailure != null) {
                throw new CommandLine.ParameterException(
                        sysFilesCommand.getYahcli().getSpec().commandLine(),
                        "Option 'restartFromFailure' only makes sense for a special file");
            }
        }

        final String normalizedSysFile = ParseUtils.normalizePossibleIdLiteral(config, sysFile);
        var delegate = isSpecialFile(config)
                ? new SysFileUploadSuite(
                        bytesPerAppend, appendsPerBurst, restartFromFailure, srcDir, config, normalizedSysFile, dryRun)
                : new SysFileUploadSuite(srcDir, config, normalizedSysFile, dryRun);

        delegate.runSuiteSync();

        final var finalSpecs = delegate.getFinalSpecs();
        if (!finalSpecs.isEmpty()) {
            if (finalSpecs.getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
                COMMON_MESSAGES.info("SUCCESS - Uploaded all requested system files");
            } else {
                COMMON_MESSAGES.warn("FAILED Uploading requested system files");
                return 1;
            }
        }

        return 0;
    }

    private boolean isSpecialFile(ConfigManager config) {
        final var normalizedSysFile = ParseUtils.normalizePossibleIdLiteral(config, sysFile);
        return "software-zip".equals(normalizedSysFile)
                || "150".equals(normalizedSysFile)
                || "telemetry-zip".equals(normalizedSysFile)
                || "159".equals(normalizedSysFile);
    }
}
