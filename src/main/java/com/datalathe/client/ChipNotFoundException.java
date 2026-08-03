package com.datalathe.client;

/**
 * Thrown when a request references a chip whose data is no longer available
 * (typically because the underlying S3 object has expired via lifecycle policy).
 *
 * <p>Recovery pattern: catch this exception, re-stage the chip from your own
 * source-of-truth using the same chipId, then retry the original call.
 */
public class ChipNotFoundException extends DatalatheApiException {
    private static final long serialVersionUID = 1L;

    private final String chipId;

    public ChipNotFoundException(String chipId, String message) {
        super(message, 404, "chip_not_found", message);
        this.chipId = chipId;
    }

    public String getChipId() {
        return chipId;
    }
}
