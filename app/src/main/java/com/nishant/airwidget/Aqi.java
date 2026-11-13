package com.nishant.airwidget;

/** PM2.5 (ug/m3) -> AQI. */
final class Aqi {
    // {pmLow, pmHigh, aqiLow, aqiHigh}
    private static final double[][] IN = {
            {0, 30, 0, 50}, {30, 60, 51, 100}, {60, 90, 101, 200},
            {90, 120, 201, 300}, {120, 250, 301, 400}, {250, 500, 401, 500}};
    private static final double[][] US = {
            {0, 9.0, 0, 50}, {9.1, 35.4, 51, 100}, {35.5, 55.4, 101, 150},
            {55.5, 125.4, 151, 200}, {125.5, 225.4, 201, 300}, {225.5, 500, 301, 500}};

    /** "MI" = what Mi Home shows: the purifier's own "aqi" value, i.e. PM2.5 in ug/m3 as-is. */
    static int fromPm25(double pm, String std) {
        if ("MI".equals(std)) return (int) Math.round(pm);
        double[][] t = "US".equals(std) ? US : IN;
        for (double[] b : t) {
            if (pm <= b[1] || b == t[t.length - 1]) {
                double lo = b[0], hi = b[1];
                double v = (b[3] - b[2]) / (hi - lo) * (Math.min(pm, hi) - lo) + b[2];
                return (int) Math.round(v);
            }
        }
        return 500;
    }

    /** Plain-words air quality for a PM2.5 value (India CPCB bands). */
    static String category(double pm) {
        if (pm <= 30) return "Good";
        if (pm <= 60) return "Satisfactory";
        if (pm <= 90) return "Moderate";
        if (pm <= 120) return "Poor";
        if (pm <= 250) return "Very poor";
        return "Severe";
    }

    /** Colour for a reading; the colour band always follows the India scale so "MI" values get colours too. */
    static int colorFor(double pm, String std) {
        return color("MI".equals(std) ? fromPm25(pm, "IN") : fromPm25(pm, std));
    }

    /** Colour for the AQI number. */
    static int color(int aqi) {
        if (aqi <= 50) return 0xFF66BB6A;
        if (aqi <= 100) return 0xFFC6E04A;
        if (aqi <= 200) return 0xFFFFB74D;
        if (aqi <= 300) return 0xFFFF7043;
        if (aqi <= 400) return 0xFFE05A8E;
        return 0xFFB06AD0;
    }
}
