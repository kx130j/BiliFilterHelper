package com.antigravity.bilifilter.config;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局过滤配置与运行状态中心
 */
public final class FilterConfig {
    private static final String PREFS_NAME = "bili_filter_prefs";
    private static SharedPreferences prefs;

    private static boolean enableRocketPromoSkip = true;
    private static boolean enableHomePromoMask = true;
    private static boolean enableInspectorFloatingBall = false;

    private static Set<String> promoKeywords = new LinkedHashSet<>(Arrays.asList(
            "创作推广",
            "商业推广",
            "花火推广",
            "起飞推广",
            "品牌推广",
            "推广",
            "赞助"
    ));

    public static volatile int skippedRocketCount = 0;
    public static final CopyOnWriteArrayList<String> recentLogs = new CopyOnWriteArrayList<>();
    public static Runnable onLogUpdated;

    private FilterConfig() {}

    public static synchronized void init(Context context) {
        if (prefs != null) return;
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        enableRocketPromoSkip = prefs.getBoolean("enable_rocket_skip", true);
        enableHomePromoMask = prefs.getBoolean("enable_home_promo_mask", true);
        enableInspectorFloatingBall = prefs.getBoolean("enable_inspector_ball", false);
        Set<String> saved = prefs.getStringSet("promo_keywords", null);
        if (saved != null && !saved.isEmpty()) {
            promoKeywords = new LinkedHashSet<>(saved);
        }
    }

    public static boolean isEnableRocketPromoSkip() {
        return enableRocketPromoSkip;
    }

    public static void setEnableRocketPromoSkip(boolean value) {
        enableRocketPromoSkip = value;
        if (prefs != null) prefs.edit().putBoolean("enable_rocket_skip", value).apply();
    }

    public static boolean isEnableHomePromoMask() {
        return enableHomePromoMask;
    }

    public static void setEnableHomePromoMask(boolean value) {
        enableHomePromoMask = value;
        if (prefs != null) prefs.edit().putBoolean("enable_home_promo_mask", value).apply();
    }

    public static boolean isEnableInspectorFloatingBall() {
        return enableInspectorFloatingBall;
    }

    public static void setEnableInspectorFloatingBall(boolean value) {
        enableInspectorFloatingBall = value;
        if (prefs != null) prefs.edit().putBoolean("enable_inspector_ball", value).apply();
    }

    public static Set<String> getPromoKeywords() {
        return promoKeywords;
    }

    public static void setPromoKeywords(Set<String> value) {
        promoKeywords = new LinkedHashSet<>(value);
        if (prefs != null) prefs.edit().putStringSet("promo_keywords", promoKeywords).apply();
    }

    public static void addLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        recentLogs.add(0, "[" + time + "] " + message);
        while (recentLogs.size() > 80) {
            recentLogs.remove(recentLogs.size() - 1);
        }
        Runnable callback = onLogUpdated;
        if (callback != null) {
            callback.run();
        }
    }
}
