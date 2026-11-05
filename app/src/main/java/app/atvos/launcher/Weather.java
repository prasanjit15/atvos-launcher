package app.atvos.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/**
 * Privacy-preserving live weather using Open-Meteo (no API keys, no tracking).
 * Runs on a single background worker, caches results in SharedPreferences, and delivers updates on the main thread.
 */
final class Weather {

    static final String KEY_ENABLED = "weather_enabled";
    static final String KEY_FAHRENHEIT = "weather_fahrenheit";
    static final String KEY_CITY = "weather_city";
    private static final String KEY_CACHED_TEMP = "weather_cached_temp";
    private static final String KEY_CACHED_CODE = "weather_cached_code";
    private static final String KEY_CACHED_IS_DAY = "weather_cached_is_day";
    private static final String KEY_CACHED_TIME = "weather_cached_time";
    private static final String KEY_CACHED_LAT = "weather_cached_lat";
    private static final String KEY_CACHED_LON = "weather_cached_lon";
    private static final String KEY_GEOCODED_NAME = "weather_geocoded_name";

    private static final long REFRESH_INTERVAL_MINUTES = 30;
    private static final long CACHE_VALID_MS = REFRESH_INTERVAL_MINUTES * 60 * 1000L;
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_BYTES = 16384;

    record WeatherInfo(String temperature, int weatherCode, boolean isDay) {
    }

    interface Listener {
        void onWeatherChanged(WeatherInfo info);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "WeatherWorker");
        t.setDaemon(true);
        return t;
    });

    private Listener listener;
    private ScheduledFuture<?> periodicTask;
    private volatile WeatherInfo currentInfo;

    Weather(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
    }

    void start(Listener listener) {
        this.listener = listener;
        loadCache();
        periodicTask = executor.scheduleWithFixedDelay(this::refreshIfNeeded, 0, REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    void stop() {
        if (periodicTask != null) {
            periodicTask.cancel(false);
            periodicTask = null;
        }
        listener = null;
    }

    void forceRefresh() {
        executor.execute(this::fetchNetwork);
    }

    WeatherInfo current() {
        return isEnabled() ? currentInfo : null;
    }

    boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) {
            notifyListener(null);
        } else {
            notifyListener(currentInfo);
            forceRefresh();
        }
    }

    boolean isFahrenheit() {
        if (prefs.contains(KEY_FAHRENHEIT)) {
            return prefs.getBoolean(KEY_FAHRENHEIT, false);
        }
        String country = Locale.getDefault().getCountry();
        return "US".equalsIgnoreCase(country) || "BS".equalsIgnoreCase(country) ||
                "BZ".equalsIgnoreCase(country) || "KY".equalsIgnoreCase(country) || "PW".equalsIgnoreCase(country);
    }

    void setFahrenheit(boolean fahrenheit) {
        prefs.edit().putBoolean(KEY_FAHRENHEIT, fahrenheit).apply();
        forceRefresh();
    }

    String city() {
        return prefs.getString(KEY_CITY, "").trim();
    }

    void setCity(String city) {
        prefs.edit()
                .putString(KEY_CITY, city != null ? city.trim() : "")
                .remove(KEY_CACHED_LAT)
                .remove(KEY_CACHED_LON)
                .remove(KEY_GEOCODED_NAME)
                .apply();
        forceRefresh();
    }

    static String autoCityName() {
        String tzId = TimeZone.getDefault().getID();
        if (tzId == null || tzId.isEmpty()) return "Auto";
        int slash = tzId.lastIndexOf('/');
        String city = slash >= 0 ? tzId.substring(slash + 1) : tzId;
        return city.replace('_', ' ');
    }

    static int iconResource(int code, boolean isDay) {
        if (code == 0) return isDay ? R.drawable.ic_weather_sun : R.drawable.ic_moon;
        if (code <= 3) return R.drawable.ic_weather_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_weather_fog;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_weather_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_weather_snow;
        if (code >= 95) return R.drawable.ic_weather_thunder;
        return R.drawable.ic_weather_cloud;
    }

    private void loadCache() {
        if (!isEnabled()) return;
        if (prefs.contains(KEY_CACHED_TEMP) && prefs.contains(KEY_CACHED_CODE)) {
            String temp = prefs.getString(KEY_CACHED_TEMP, null);
            int code = prefs.getInt(KEY_CACHED_CODE, 0);
            boolean isDay = prefs.getBoolean(KEY_CACHED_IS_DAY, true);
            if (temp != null) {
                currentInfo = new WeatherInfo(temp, code, isDay);
                notifyListener(currentInfo);
            }
        }
    }

    private void refreshIfNeeded() {
        if (!isEnabled()) return;
        long last = prefs.getLong(KEY_CACHED_TIME, 0);
        long now = System.currentTimeMillis();
        if (now - last > CACHE_VALID_MS || currentInfo == null) {
            fetchNetwork();
        }
    }

    private void fetchNetwork() {
        if (!isEnabled()) return;
        try {
            double[] coords = resolveCoordinates();
            if (coords == null) return;

            boolean fahrenheit = isFahrenheit();
            String urlStr = "https://api.open-meteo.com/v1/forecast?latitude=" + coords[0] +
                    "&longitude=" + coords[1] +
                    "&current=temperature_2m,weather_code,is_day" +
                    (fahrenheit ? "&temperature_unit=fahrenheit" : "");

            String jsonText = downloadText(urlStr);
            if (jsonText == null) return;

            JSONObject root = new JSONObject(jsonText);
            JSONObject current = root.optJSONObject("current");
            if (current == null) return;

            double temp = current.getDouble("temperature_2m");
            int code = current.getInt("weather_code");
            boolean isDay = current.optInt("is_day", 1) == 1;

            String tempStr = Math.round(temp) + "°";
            currentInfo = new WeatherInfo(tempStr, code, isDay);

            prefs.edit()
                    .putString(KEY_CACHED_TEMP, tempStr)
                    .putInt(KEY_CACHED_CODE, code)
                    .putBoolean(KEY_CACHED_IS_DAY, isDay)
                    .putLong(KEY_CACHED_TIME, System.currentTimeMillis())
                    .apply();

            notifyListener(currentInfo);
        } catch (Throwable ignored) {
            // Fails safe on network errors or bad response: keeps existing cached weather
        }
    }

    private double[] resolveCoordinates() {
        String customCity = city();
        String targetName = customCity.isEmpty() ? autoCityName() : customCity;

        if (prefs.contains(KEY_CACHED_LAT) && prefs.contains(KEY_CACHED_LON)) {
            String cachedName = prefs.getString(KEY_GEOCODED_NAME, null);
            if (targetName.equalsIgnoreCase(cachedName)) {
                return new double[]{
                        Double.longBitsToDouble(prefs.getLong(KEY_CACHED_LAT, 0)),
                        Double.longBitsToDouble(prefs.getLong(KEY_CACHED_LON, 0))
                };
            }
        }

        try {
            String encoded = URLEncoder.encode(targetName, "UTF-8");
            String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1&language=en&format=json";
            String jsonText = downloadText(geoUrl);
            if (jsonText != null) {
                JSONObject root = new JSONObject(jsonText);
                JSONArray results = root.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject first = results.getJSONObject(0);
                    double lat = first.getDouble("latitude");
                    double lon = first.getDouble("longitude");
                    prefs.edit()
                            .putLong(KEY_CACHED_LAT, Double.doubleToRawLongBits(lat))
                            .putLong(KEY_CACHED_LON, Double.doubleToRawLongBits(lon))
                            .putString(KEY_GEOCODED_NAME, targetName)
                            .apply();
                    return new double[]{lat, lon};
                }
            }
        } catch (Throwable ignored) {
        }

        // Fallback default if geocoding cannot resolve
        return null;
    }

    private static String downloadText(String urlStr) {
        HttpsURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpsURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "atvOS-Launcher/1.0");

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                return null;
            }

            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) break;
                out.write(buffer, 0, read);
            }
            in.close();
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void notifyListener(WeatherInfo info) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onWeatherChanged(info);
            }
        });
    }
}

