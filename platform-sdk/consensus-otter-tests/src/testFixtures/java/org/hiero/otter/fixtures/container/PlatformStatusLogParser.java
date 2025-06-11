package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hiero.consensus.model.status.PlatformStatus;
import org.testcontainers.containers.output.OutputFrame;

public class PlatformStatusLogParser implements Consumer<OutputFrame> {

    private static final Pattern PLATFORM_STATUS_PATTERN = Pattern.compile("Platform spent \\d*\\.\\d* s in [A-Z]+\\. Now in ([A-Z]+)");

    private final Consumer<PlatformStatus> target;

    public PlatformStatusLogParser(@NonNull final Consumer<PlatformStatus> target) {
        this.target = requireNonNull(target);
    }

    @Override
    public void accept(final OutputFrame outputFrame) {
        final Matcher matcher = PLATFORM_STATUS_PATTERN.matcher(outputFrame.getUtf8String());
        if (matcher.find()) {
            final String statusString = matcher.group(1);
            final PlatformStatus platformStatus = PlatformStatus.valueOf(statusString);
            target.accept(platformStatus);
        }
    }
}
