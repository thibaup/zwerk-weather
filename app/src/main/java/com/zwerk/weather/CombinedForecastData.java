package com.zwerk.weather;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Multi-day temperature and precipitation series shared by the app view and 4x2 widget. */
final class CombinedForecastData {
    static final int MIN_WIDGET_DAYS = 4;
    static final int DEFAULT_WIDGET_DAYS = 5;
    static final int MAX_WIDGET_DAYS = 7;

    static final String PRECIP_STYLE_COLUMNS = "columns";
    static final String PRECIP_STYLE_LINE = "line";

    static final class DayPoint {
        final LocalDate date;
        final long startMillis;
        final long endMillis;
        final Integer high;
        final Integer low;
        final double precipitationMm;
        final int probability;
        final String conditionType;
        final String condition;
        final boolean today;

        DayPoint(LocalDate date, long startMillis, long endMillis, Integer high, Integer low,
                double precipitationMm, int probability, String conditionType, String condition,
                boolean today) {
            this.date = date;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.high = high;
            this.low = low;
            this.precipitationMm = precipitationMm;
            this.probability = probability;
            this.conditionType = conditionType == null ? "" : conditionType;
            this.condition = condition == null ? "" : condition;
            this.today = today;
        }
    }

    final List<DayPoint> days;
    final boolean fahrenheit;
    final boolean hasTemperatures;
    final int minTemperature;
    final int maxTemperature;
    final boolean hasPrecipitationAmount;
    final boolean hasPrecipitationProbability;
    final boolean usingProbabilityScale;
    final double peakMm;
    final double totalMm;
    final double scaleMaxDisplay;

    private CombinedForecastData(List<DayPoint> days, boolean fahrenheit) {
        this.days = Collections.unmodifiableList(days);
        this.fahrenheit = fahrenheit;
        int minT = Integer.MAX_VALUE;
        int maxT = Integer.MIN_VALUE;
        boolean hasAmount = false;
        boolean hasProb = false;
        double peak = 0d;
        double total = 0d;
        for (DayPoint day : days) {
            if (day.high != null) {
                minT = Math.min(minT, day.high);
                maxT = Math.max(maxT, day.high);
            }
            if (day.low != null) {
                minT = Math.min(minT, day.low);
                maxT = Math.max(maxT, day.low);
            }
            if (Double.isFinite(day.precipitationMm) && day.precipitationMm >= 0d) {
                hasAmount = true;
                peak = Math.max(peak, day.precipitationMm);
                total += day.precipitationMm;
            }
            if (day.probability >= 0) {
                hasProb = true;
            }
        }
        hasTemperatures = minT != Integer.MAX_VALUE;
        minTemperature = hasTemperatures ? minT : 0;
        maxTemperature = hasTemperatures ? maxT : 1;
        hasPrecipitationAmount = hasAmount;
        hasPrecipitationProbability = hasProb;
        usingProbabilityScale = !hasAmount && hasProb;
        peakMm = peak;
        totalMm = total;
        scaleMaxDisplay = usingProbabilityScale ? 100d : dailyRainScaleMax(peak, fahrenheit);
    }

    boolean isEmpty() {
        return days.isEmpty();
    }

    /** Normalized [0..1] precipitation height for this day on the active chart scale. */
    float precipitationFraction(DayPoint day) {
        if (day == null) return Float.NaN;
        if (usingProbabilityScale) {
            if (day.probability < 0) return Float.NaN;
            return Math.max(0f, Math.min(1f, day.probability / 100f));
        }
        if (!Double.isFinite(day.precipitationMm) || day.precipitationMm < 0d) return Float.NaN;
        double display = toDisplayAmount(day.precipitationMm, fahrenheit);
        if (scaleMaxDisplay <= 0d) return 0f;
        return (float) Math.max(0d, Math.min(1d, display / scaleMaxDisplay));
    }

    boolean hasPositivePrecipitation() {
        if (usingProbabilityScale) {
            for (DayPoint day : days) {
                if (day.probability > 0) return true;
            }
            return false;
        }
        return peakMm > 0d;
    }

    static int clampWidgetDays(int days) {
        return Math.max(MIN_WIDGET_DAYS, Math.min(MAX_WIDGET_DAYS, days));
    }

    static String normalizePrecipitationStyle(String style) {
        return PRECIP_STYLE_LINE.equalsIgnoreCase(style == null ? "" : style.trim())
                ? PRECIP_STYLE_LINE : PRECIP_STYLE_COLUMNS;
    }

    static CombinedForecastData fromWidgetTimeline(WeatherWidgetTimeline timeline, long now,
            int requestedDays, boolean fahrenheit) {
        int limit = clampWidgetDays(requestedDays);
        List<DayPoint> points = new ArrayList<>();
        if (timeline == null || !timeline.retained || timeline.days == null) {
            return new CombinedForecastData(points, fahrenheit);
        }
        LocalDate todayDate = Instant.ofEpochMilli(now).atZone(timeline.zone).toLocalDate();
        for (int i = 0; i < timeline.days.length() && points.size() < limit; i++) {
            JSONObject day = timeline.days.optJSONObject(i);
            if (day == null) continue;
            long start = day.optLong("start", 0L);
            long end = day.optLong("end", 0L);
            LocalDate date = parseIsoDate(day.optString("date", ""));
            if (date == null && start > 0L) {
                date = Instant.ofEpochMilli(start).atZone(timeline.zone).toLocalDate();
            }
            if (end > 0L && end <= now) continue;
            if (end <= 0L && date != null && date.isBefore(todayDate)) continue;

            Integer high = intOrNull(day, "high");
            Integer low = intOrNull(day, "low");
            if (high == null && low == null) continue;

            double amountMm = day.has("precipitationMm") && !day.isNull("precipitationMm")
                    ? day.optDouble("precipitationMm", Double.NaN) : Double.NaN;
            if (Double.isFinite(amountMm) && amountMm < 0d) amountMm = Double.NaN;
            int probability = day.has("precipitation") && !day.isNull("precipitation")
                    ? clampPercent(day.optInt("precipitation", -1)) : -1;
            String conditionType = clean(day.optString("conditionType", ""));
            String condition = clean(day.optString("condition", ""));

            // Legacy cached snapshots written before daily details were persisted can
            // still be enriched from the same snapshot's hourly intervals without fabricating data.
            if (timeline.hours != null && timeline.hours.length() > 0
                    && (!Double.isFinite(amountMm) || probability < 0 || conditionType.isEmpty())) {
                HourlyDayFallback fallback = aggregateHourlyFallback(
                        timeline.hours, start, end, date, timeline.zone);
                if (!Double.isFinite(amountMm) && Double.isFinite(fallback.amountMm)) {
                    amountMm = fallback.amountMm;
                }
                if (probability < 0 && fallback.maxProbability >= 0) {
                    probability = fallback.maxProbability;
                }
                if (conditionType.isEmpty() && !fallback.conditionType.isEmpty()) {
                    conditionType = fallback.conditionType;
                    if (condition.isEmpty()) condition = fallback.condition;
                }
            }

            boolean isToday = (start > 0L && end > start && ForecastClock.contains(start, end, now))
                    || (date != null && date.equals(todayDate));
            points.add(new DayPoint(date, start, end, high, low, amountMm, probability,
                    conditionType, condition, isToday));
        }
        return new CombinedForecastData(points, fahrenheit);
    }

    private static final class HourlyDayFallback {
        double amountMm = Double.NaN;
        int maxProbability = -1;
        String conditionType = "";
        String condition = "";
    }

    private static HourlyDayFallback aggregateHourlyFallback(JSONArray hours, long dayStart,
            long dayEnd, LocalDate date, ZoneId zone) {
        HourlyDayFallback out = new HourlyDayFallback();
        double sum = 0d;
        boolean anyAmount = false;
        int bestSeverity = -1;
        for (int i = 0; i < hours.length(); i++) {
            JSONObject hour = hours.optJSONObject(i);
            if (hour == null) continue;
            long hStart = hour.optLong("start", 0L);
            if (hStart <= 0L) continue;
            boolean matches;
            if (dayStart > 0L && dayEnd > dayStart) {
                matches = hStart >= dayStart && hStart < dayEnd;
            } else if (date != null) {
                matches = Instant.ofEpochMilli(hStart).atZone(zone).toLocalDate().equals(date);
            } else {
                matches = false;
            }
            if (!matches) continue;
            double mm = hour.optDouble("precipitationMm", Double.NaN);
            if (Double.isFinite(mm) && mm >= 0d) {
                sum += mm;
                anyAmount = true;
            }
            int p = clampPercent(hour.optInt("precipitation", -1));
            if (p >= 0) out.maxProbability = Math.max(out.maxProbability, p);
            String type = clean(hour.optString("conditionType", ""));
            if (!type.isEmpty()) {
                int severity = conditionSeverity(type);
                boolean daytime = hour.optBoolean("daytime", true);
                int score = severity * 2 + (daytime ? 1 : 0);
                if (score > bestSeverity) {
                    bestSeverity = score;
                    out.conditionType = type;
                    out.condition = clean(hour.optString("condition", type));
                }
            }
        }
        if (anyAmount) out.amountMm = sum;
        return out;
    }

    private static int conditionSeverity(String conditionType) {
        String c = conditionType.toLowerCase(Locale.ROOT);
        if (c.contains("thunder") || c.contains("storm")) return 6;
        if (c.contains("snow") || c.contains("sleet") || c.contains("ice") || c.contains("flurr")) return 5;
        if (c.contains("rain") || c.contains("drizzle") || c.contains("shower")) return 4;
        if (c.contains("fog") || c.contains("mist") || c.contains("haze")) return 3;
        if (c.contains("cloud") || c.contains("overcast")) return c.contains("part") ? 1 : 2;
        return 0;
    }

    /** Daily total precipitation in millimetres from Open-Meteo or Google daily JSON. */
    static double dayPrecipitationMm(JSONObject day) {
        if (day == null) return Double.NaN;
        if (day.has("precipitationMm") && !day.isNull("precipitationMm")) {
            double direct = day.optDouble("precipitationMm", Double.NaN);
            if (Double.isFinite(direct) && direct >= 0d) return direct;
        }
        JSONObject top = day.optJSONObject("precipitation");
        if (top != null) {
            double direct = PrecipitationWidgetData.quantityMm(top.optJSONObject("qpf"));
            if (Double.isFinite(direct)) return direct;
        }
        double dayMm = periodPrecipitationMm(day.optJSONObject("daytimeForecast"));
        double nightMm = periodPrecipitationMm(day.optJSONObject("nighttimeForecast"));
        if (Double.isFinite(dayMm) && Double.isFinite(nightMm)) return dayMm + nightMm;
        if (Double.isFinite(dayMm)) return dayMm;
        if (Double.isFinite(nightMm)) return nightMm;
        return Double.NaN;
    }

    private static double periodPrecipitationMm(JSONObject period) {
        if (period == null) return Double.NaN;
        JSONObject precip = period.optJSONObject("precipitation");
        return precip == null ? Double.NaN : PrecipitationWidgetData.quantityMm(precip.optJSONObject("qpf"));
    }

    /** Daily maximum precipitation probability (0-100), or -1 when unavailable. */
    static int dayProbability(JSONObject day) {
        if (day == null) return -1;
        if (day.has("precipitation") && !(day.opt("precipitation") instanceof JSONObject)
                && !day.isNull("precipitation")) {
            int direct = clampPercent(day.optInt("precipitation", -1));
            if (direct >= 0) return direct;
        }
        int best = -1;
        int top = probabilityOf(day);
        int daytime = probabilityOf(day.optJSONObject("daytimeForecast"));
        int nighttime = probabilityOf(day.optJSONObject("nighttimeForecast"));
        if (top >= 0) best = Math.max(best, top);
        if (daytime >= 0) best = Math.max(best, daytime);
        if (nighttime >= 0) best = Math.max(best, nighttime);
        return best;
    }

    private static int probabilityOf(JSONObject object) {
        if (object == null) return -1;
        JSONObject precip = object.optJSONObject("precipitation");
        if (precip == null) return -1;
        JSONObject probability = precip.optJSONObject("probability");
        if (probability == null || !probability.has("percent") || probability.isNull("percent")) return -1;
        return clampPercent(probability.optInt("percent", -1));
    }

    static String dayConditionType(JSONObject day) {
        if (day == null) return "";
        String direct = clean(day.optString("conditionType", ""));
        if (!direct.isEmpty()) return direct;
        JSONObject[] sources = {
                day.optJSONObject("daytimeForecast"),
                day,
                day.optJSONObject("nighttimeForecast")
        };
        for (JSONObject source : sources) {
            if (source == null) continue;
            String glyph = clean(SceneSpec.glyphCondition(source));
            if (!glyph.isEmpty() && !"Unknown".equalsIgnoreCase(glyph)) return glyph;
        }
        return "";
    }

    static String dayConditionDescription(JSONObject day) {
        if (day == null) return "";
        String direct = clean(day.optString("condition", ""));
        if (!direct.isEmpty()) return direct;
        JSONObject[] sources = {
                day.optJSONObject("daytimeForecast"),
                day,
                day.optJSONObject("nighttimeForecast")
        };
        for (JSONObject source : sources) {
            if (source == null) continue;
            JSONObject condition = source.optJSONObject("weatherCondition");
            if (condition == null) continue;
            JSONObject desc = condition.optJSONObject("description");
            String text = desc == null ? "" : clean(desc.optString("text", ""));
            if (!text.isEmpty()) return text;
            String type = clean(condition.optString("type", ""));
            if (!type.isEmpty()) return type;
        }
        return "";
    }

    static String dayIsoDate(JSONObject day, ZoneId zone) {
        if (day == null) return "";
        JSONObject displayDate = day.optJSONObject("displayDate");
        if (displayDate != null) {
            int year = displayDate.optInt("year", -1);
            int month = displayDate.optInt("month", -1);
            int d = displayDate.optInt("day", -1);
            if (year >= 1 && month >= 1 && d >= 1) {
                try { return LocalDate.of(year, month, d).toString(); }
                catch (Exception ignored) { }
            }
        }
        long start = SavedForecast.epoch(day, "startTime");
        if (start > 0L && zone != null) {
            return Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toString();
        }
        return "";
    }

    static double toDisplayAmount(double millimeters, boolean fahrenheit) {
        if (!Double.isFinite(millimeters)) return Double.NaN;
        return fahrenheit ? millimeters / 25.4d : millimeters;
    }

    static double dailyRainScaleMax(double peakMm, boolean fahrenheit) {
        if (fahrenheit) {
            double peakIn = Math.max(0d, peakMm) / 25.4d;
            if (peakIn <= 0.4d) return 0.4d;
            if (peakIn <= 1.0d) return 1.0d;
            if (peakIn <= 2.0d) return 2.0d;
            return Math.ceil(peakIn);
        }
        double peak = Math.max(0d, peakMm);
        if (peak <= 10.0d) return 10.0d;
        if (peak <= 20.0d) return 20.0d;
        if (peak <= 30.0d) return 30.0d;
        if (peak <= 50.0d) return 50.0d;
        return Math.ceil(peak / 25.0d) * 25.0d;
    }

    static String formatAmount(double displayAmount, boolean fahrenheit, boolean includeUnit) {
        if (!Double.isFinite(displayAmount) || displayAmount < 0d) return "—";
        String unit = includeUnit ? (fahrenheit ? " in" : " mm") : "";
        if (displayAmount == 0d) return "0" + unit;
        if (fahrenheit) {
            if (displayAmount < 0.01d) return "<0.01" + unit;
            return String.format(Locale.getDefault(), displayAmount >= 1d ? "%.1f" : "%.2f", displayAmount) + unit;
        }
        if (displayAmount < 0.1d) return "<0.1" + unit;
        if (displayAmount >= 10d) return String.format(Locale.getDefault(), "%.0f", displayAmount) + unit;
        return String.format(Locale.getDefault(), "%.1f", displayAmount) + unit;
    }

    static String formatCompactAmount(double millimeters, boolean fahrenheit, boolean includeUnit) {
        if (!Double.isFinite(millimeters) || millimeters < 0d) return "—";
        double display = toDisplayAmount(millimeters, fahrenheit);
        String unit = includeUnit ? (fahrenheit ? "in" : "mm") : "";
        if (display == 0d) return "0" + unit;
        if (fahrenheit) {
            if (display < 0.01d) return "<.01" + unit;
            return String.format(Locale.getDefault(), display >= 1d ? "%.1f" : "%.2f", display) + unit;
        }
        if (display < 0.1d) return "<0.1" + unit;
        if (display >= 10d) return String.format(Locale.getDefault(), "%.0f", display) + unit;
        return String.format(Locale.getDefault(), "%.1f", display) + unit;
    }

    static String scaleLabel(double scaleMaxDisplay, boolean usingProbability, boolean fahrenheit) {
        if (usingProbability) return "100%";
        if (fahrenheit) {
            return String.format(Locale.getDefault(), scaleMaxDisplay >= 1d ? "%.0f in" : "%.2f in", scaleMaxDisplay);
        }
        if (Math.abs(scaleMaxDisplay - Math.rint(scaleMaxDisplay)) < 1e-6) {
            return Math.round(scaleMaxDisplay) + " mm";
        }
        return String.format(Locale.getDefault(), "%.1f mm", scaleMaxDisplay);
    }

    private static LocalDate parseIsoDate(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try { return LocalDate.parse(value.trim()); }
        catch (Exception ignored) { return null; }
    }

    private static Integer intOrNull(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return null;
        try { return object.getInt(key); }
        catch (Exception ignored) { return null; }
    }

    private static int clampPercent(int value) {
        return value < 0 ? -1 : Math.min(100, value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
