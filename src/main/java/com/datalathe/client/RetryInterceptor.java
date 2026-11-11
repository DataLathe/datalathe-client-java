package com.datalathe.client;

import okhttp3.Interceptor;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

final class RetryInterceptor implements Interceptor {
    private static final int MAX_JITTER_MILLIS = 250;
    static final long MAX_UNAVAILABLE_RETRY_AFTER_SECONDS = 30;

    private final RetryConfig config;

    RetryInterceptor(RetryConfig config) {
        this.config = config;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        for (int attempt = 0; isRetryable(response) && attempt < config.getMaxRetries(); attempt++) {
            long waitMillis = waitMillis(response, attempt);
            ResponseBody buffered = response.peekBody(Long.MAX_VALUE);
            response.close();
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return response.newBuilder().body(buffered).build();
            }
            buffered.close();
            response = chain.proceed(chain.request());
        }
        return response;
    }

    private static boolean isRetryable(Response response) {
        if (response.code() == 429) {
            return true;
        }
        if (response.code() != 503) {
            return false;
        }
        String retryAfter = response.header("Retry-After");
        if (retryAfter == null) {
            return false;
        }
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            return seconds >= 0 && seconds <= MAX_UNAVAILABLE_RETRY_AFTER_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private long waitMillis(Response response, int attempt) {
        long waitMillis = -1;
        String retryAfter = response.header("Retry-After");
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds >= 0) {
                    waitMillis = Math.min(seconds, config.getMaxWaitMillis() / 1000) * 1000;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (waitMillis < 0) {
            waitMillis = Math.min(config.getBackoffBaseMillis() << attempt, config.getMaxWaitMillis());
        }
        return waitMillis + ThreadLocalRandom.current().nextLong(MAX_JITTER_MILLIS + 1);
    }
}
