package com.datalathe.client.results;

import java.util.Locale;

final class NonFiniteNumbers {
    private NonFiniteNumbers() {}

    static double parseDouble(String value) {
        Double nonFinite = nonFinite(value);
        return nonFinite != null ? nonFinite : Double.parseDouble(value);
    }

    static float parseFloat(String value) {
        Double nonFinite = nonFinite(value);
        return nonFinite != null ? nonFinite.floatValue() : Float.parseFloat(value);
    }

    private static Double nonFinite(String value) {
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "inf":
            case "+inf":
            case "infinity":
            case "+infinity":
                return Double.POSITIVE_INFINITY;
            case "-inf":
            case "-infinity":
                return Double.NEGATIVE_INFINITY;
            case "nan":
            case "+nan":
            case "-nan":
                return Double.NaN;
            default:
                return null;
        }
    }
}
