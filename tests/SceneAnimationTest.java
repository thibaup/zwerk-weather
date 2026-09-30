package com.zwerk.weather;

import org.json.JSONObject;

public final class SceneAnimationTest {
    private static int checks;

    public static void main(String[] args) {
        SceneSpec clear = new SceneSpec("day", "none", true, "Clear");
        SceneSpec sunny = new SceneSpec("day", "none", true, "Sunny");
        check(clear.hasSameVisuals(sunny), "condition wording does not restart an identical sky");
        check(!clear.equals(sunny), "forecast descriptions retain their own identity");
        check(!clear.hasSameVisuals(null), "missing target is not a matching scene");
        check(!clear.hasSameVisuals(new SceneSpec("night", "none", false, "Clear")),
                "nightfall changes the sky");
        check(!clear.hasSameVisuals(new SceneSpec("day", "cloud", true, "Cloudy")),
                "cloud changes still transition");
        check(!new SceneSpec("day", "partly", true, "Partly cloudy").hasSameVisuals(
                new SceneSpec("day", "cloud", true, "Overcast")),
                "cloud density changes still transition");
        SceneSpec rain = new SceneSpec("rain", "rain", true, "Rain");
        check(rain.hasSameVisuals(new SceneSpec("rain", "rain", true, "Rain showers")),
                "identical rain continues without resetting the fade");
        check(!rain.hasSameVisuals(new SceneSpec("rain", "rain", true, "Heavy rain")),
                "a real rain intensity change still transitions");
        SceneSpec light = new SceneSpec("rain", "rain", true, "Light rain");
        check(light.hasSameVisuals(new SceneSpec("rain", "rain", true, "Drizzle")),
                "equivalent light rain conditions keep their animation");
        check(light.rainIntensity == .60f && rain.rainIntensity == 1f,
                "light and normal rain preserve existing intensity");
        check(new SceneSpec("rain", "rain", true, "Heavy rain").rainIntensity == 1.28f,
                "heavy rain preserves existing intensity");
        SceneSpec thunder = new SceneSpec("rain", "thunder", true, "Thunderstorm");
        check(thunder.rainIntensity == 1.28f && thunder.hasSameVisuals(
                new SceneSpec("rain", "thunder", true, "Light thunderstorm rain")),
                "thunder always uses storm intensity without restarting for wording");
        check(!rain.hasSameVisuals(thunder), "entering thunder still transitions");
        check(!rain.hasSameVisuals(new SceneSpec("snow", "snow", true, "Snow")),
                "precipitation type changes still transition");
        JSONObject providerHour = new JSONObject().put("isDaytime", true)
                .put("weatherCondition", new JSONObject().put("type", "LIGHT_RAIN")
                        .put("description", new JSONObject().put("text", "Light rain")));
        check(SceneSpec.fromWeather(providerHour, false).hasSameVisuals(light),
                "provider hourly data selects the matching rendered scene");
        System.out.println(checks + " scene animation checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
