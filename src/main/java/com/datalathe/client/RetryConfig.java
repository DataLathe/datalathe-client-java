package com.datalathe.client;

import lombok.Builder;
import lombok.Value;

/**
 * Controls automatic retry of HTTP 429 responses. The engine returns 429
 * when an admission gate is saturated, having done no work on the request,
 * so replaying it is always safe.
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
