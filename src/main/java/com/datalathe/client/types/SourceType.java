package com.datalathe.client.types;

public enum SourceType {
    MYSQL,
    FILE,
    S3,
    CHIP,
    /** @deprecated Use CHIP instead */
    @Deprecated
    LOCAL,
    /** @deprecated Use CHIP instead */
    @Deprecated
    CACHE
}