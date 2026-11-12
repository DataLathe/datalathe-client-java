package com.datalathe.client.resolver;

/**
 * Names the series that a resolver-created chip joins, from the tag that
 * isolates it. Return null to leave the chip out of any series.
 */
@FunctionalInterface
public interface SeriesNamer {
    String seriesId(String tagKey, String tagValue);

    /** One series per tag value, for chips isolated by {@code tagKey}. */
    static SeriesNamer tagValue(String tagKey) {
        return (key, value) -> tagKey.equals(key) ? value : null;
    }
}
