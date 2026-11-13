package com.datalathe.client;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Gives catalog calls a short read timeout and one retry.
 *
 * <p>A chip search, a chip lookup, or a tag write is answered in milliseconds.
 * The client's read timeout is sized for a staging or a report, so a catalog
 * call whose response never arrives would otherwise hold its caller for the
 * whole five minutes. These calls are safe to repeat, so a timed-out one is
 * sent once more on a fresh attempt before the timeout is reported.</p>
 */
final class QuickCallInterceptor implements Interceptor {
    private static final Logger logger = LogManager.getLogger(QuickCallInterceptor.class);

    /** Marks a request as a catalog call. */
    static final class Quick {
        static final Quick INSTANCE = new Quick();

        private Quick() {}
    }

    private final int timeoutMillis;

    QuickCallInterceptor(Duration timeout) {
        this.timeoutMillis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
    }

    static Request.Builder mark(Request.Builder builder) {
        return builder.tag(Quick.class, Quick.INSTANCE);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (request.tag(Quick.class) == null) {
            return chain.proceed(request);
        }
        Chain quick = chain.withReadTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            return quick.proceed(request);
        } catch (SocketTimeoutException e) {
            logger.warn("{} {} got no response in {} ms; sending it once more",
                    request.method(), request.url().encodedPath(), timeoutMillis);
            return quick.proceed(request);
        }
    }
}
