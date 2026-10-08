package org.hiero.base.concurrent;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import java.security.MessageDigest;
import java.util.function.Supplier;
import org.hiero.base.concurrent.internal.DefaultForkJoinWorkerThread;

public final class ThreadLocalStorage {

    private static final ThreadLocal<MessageDigest> TLS_MESSAGE_DIGEST = new ThreadLocal<>();
    private static final ThreadLocal<WritableMessageDigest> TLS_WRITABLE_MESSAGE_DIGEST = new ThreadLocal<>();

    private ThreadLocalStorage() {
    }

    public static MessageDigest getMessageDigest(final Supplier<MessageDigest> initializer) {
        if (Thread.currentThread() instanceof DefaultForkJoinWorkerThread dfjThread) {
            return dfjThread.getThreadLocalMessageDigest(initializer);
        } else {
            MessageDigest md = TLS_MESSAGE_DIGEST.get();
            if (md == null) {
                md = initializer.get();
                TLS_MESSAGE_DIGEST.set(md);
                assert TLS_WRITABLE_MESSAGE_DIGEST.get() == null;
                final WritableMessageDigest wmd = new WritableMessageDigest(md);
                TLS_WRITABLE_MESSAGE_DIGEST.set(wmd);
            }
            return md;
        }
    }

    public static WritableMessageDigest getWritableMessageDigest(final Supplier<MessageDigest> initializer) {
        if (Thread.currentThread() instanceof DefaultForkJoinWorkerThread dfjThread) {
            return dfjThread.getThreadLocalWritableMessageDigest(initializer);
        } else {
            WritableMessageDigest wmd = TLS_WRITABLE_MESSAGE_DIGEST.get();
            if (wmd == null) {
                assert TLS_MESSAGE_DIGEST.get() == null;
                final MessageDigest md = initializer.get();
                TLS_MESSAGE_DIGEST.set(md);
                wmd = new WritableMessageDigest(md);
                TLS_WRITABLE_MESSAGE_DIGEST.set(wmd);
            }
            return wmd;
        }
    }
}
