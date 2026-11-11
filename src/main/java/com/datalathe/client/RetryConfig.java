package com.datalathe.client;

import lombok.Builder;
import lombok.Value;

/**
 * Controls automatic retry of HTTP 429 responses and of short-lived HTTP 503
 * responses. The engine returns 429 when an admission gate is saturated,
 * having done no work on the request, so replaying it is always safe.
 *
 * <p>A 503 is retried, under the same {@code maxRetries} budget, only when
 * it carries a {@code Retry-After} header of 0 to 30 whole seconds. The
 * engine sends such a response while it shuts down during a deploy, before
 * it does any work on the request, and a retry reaches the replacement
 * engine. A 503 with no {@code Retry-After}, an unparseable one, or a value
 * above 30 seconds is returned to the caller without a retry.</p>
 *
 * <p>Waits between attempts honor the {@code Retry-After} header (seconds),
 * capped at {@code maxWaitMillis}; when the header is missing or
 * unparseable, exponential backoff from {@code backoffBaseMillis} is used.
 * Every wait gets 0&ndash;250ms of random jitter.</p>
 */
@Value
@Builder
public class RetryConfig {
    public static final RetryConfig DEFAULT = RetryConfig.builder().build();
    public static final RetryConfig DISABLED = RetryConfig.builder().enabled(false).build();

    @Builder.Default
    boolean enabled = true;

    @Builder.Default
    int maxRetries = 3;

    @Builder.Default
    long backoffBaseMillis = 1000;

    @Builder.Default
    long maxWaitMillis = 30_000;
}
