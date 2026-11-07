package com.antigravity.bilifilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.antigravity.bilifilter.config.FilterConfig;
import com.antigravity.bilifilter.service.BiliAccessibilityService;
import java.util.LinkedHashSet;
import java.util.Set;

public class MainActivity extends Activity {

    private TextView tvAccessibilityStatus;
    private TextView tvOverlayStatus;
    private TextView tvStatsSummary;
    private TextView tvLogConsole;

    public interface OnToggleListener {
        void onToggle(boolean isChecked);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FilterConfig.init(this);

        setContentView(buildDashboardView());

        FilterConfig.onLogUpdated = () -> runOnUiThread(this::refreshLogsAndStats);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
        refreshLogsAndStats();
    }

    @Override
    protected void onDestroy() {
        FilterConfig.onLogUpdated = null;
        super.onDestroy();
    }

    private View buildDashboardView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#FFF4F5F7"));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));

        // 顶部标题栏卡片
        LinearLayout headerCard = createCardLayout();
        GradientDrawable headerBg = new GradientDrawable();
        headerBg.setColor(Color.parseColor("#FFFB7299"));
        headerBg.setCornerRadius(dp(14));
        headerCard.setBackground(headerBg);

        TextView titleView = new TextView(this);
        titleView.setText("B站净化助手 · 免Root无障碍版");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        headerCard.addView(titleView);

        TextView subTitleView = new TextView(this);
        subTitleView.setText("竖屏火箭推广标极速自动划走 + 首页推广卡片实时滑动跟随遮罩");
        subTitleView.setTextColor(Color.parseColor("#FFFFE5EE"));
        subTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        subTitleView.setPadding(0, dp(6), 0, dp(10));
        headerCard.addView(subTitleView);

        tvStatsSummary = new TextView(this);
        tvStatsSummary.setTextColor(Color.WHITE);
        tvStatsSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvStatsSummary.setTypeface(tvStatsSummary.getTypeface(), Typeface.BOLD);
        headerCard.addView(tvStatsSummary);

        root.addView(headerCard, marginParams(14));

        // 1. 权限状态与开启卡片
        LinearLayout permCard = createCardLayout();
        permCard.addView(sectionTitle("1. 必备系统权限状态（免Root）"));

        tvAccessibilityStatus = new TextView(this);
        tvAccessibilityStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tvAccessibilityStatus.setPadding(0, dp(6), 0, dp(6));
        permCard.addView(tvAccessibilityStatus);

        permCard.addView(createActionButton("前往开启 / 检查【无障碍服务】", () ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        ));

        tvOverlayStatus = new TextView(this);
        tvOverlayStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tvOverlayStatus.setPadding(0, dp(10), 0, dp(6));
        permCard.addView(tvOverlayStatus);

        permCard.addView(createActionButton("前往开启【悬浮窗权限】（用于首页遮罩与嗅探球）", () -> {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        }));
        root.addView(permCard, marginParams(14));

        // 2. 推广视频跳过与遮罩卡片
        LinearLayout rocketCard = createCardLayout();
        rocketCard.addView(sectionTitle("2. 推广视频自动跳过与遮罩"));

        rocketCard.addView(createSwitchRow(
                "🚀 自动划走带“火箭标/创作推广”的竖屏视频",
                "刷B站竖屏视频时，识别左下角创作推广/商业推广标签、顶部广告声明或播放量右侧小火箭图标，毫秒级自动上滑跳过",
                FilterConfig.isEnableRocketPromoSkip(),
                FilterConfig::setEnableRocketPromoSkip
        ));

        rocketCard.addView(createSwitchRow(
                "🛡️ 首页推荐流推广视频卡片实时跟随遮罩",
                "对首页双列推荐流中的推广视频卡片覆盖防误触遮罩，滑动屏幕时实时读取节点位置紧贴卡片移动",
                FilterConfig.isEnableHomePromoMask(),
                FilterConfig::setEnableHomePromoMask
        ));

        rocketCard.addView(createSecondaryButton("自定义火箭标/推广识别关键词", this::showEditPromoKeywordsDialog));
        root.addView(rocketCard, marginParams(14));

        // 3. 调试与实时日志控制台
        LinearLayout debugCard = createCardLayout();
        debugCard.addView(sectionTitle("3. 节点嗅探与实时拦截日志"));

        debugCard.addView(createSwitchRow(
                "🔍 显示屏幕“嗅探节点”调试悬浮球",
                "开启后可在B站任意界面点击悬浮球，一键抓取当前屏幕所有控件的 ID、文本与坐标",
                FilterConfig.isEnableInspectorFloatingBall(),
                isChecked -> {
                    FilterConfig.setEnableInspectorFloatingBall(isChecked);
                    if (BiliAccessibilityService.instance != null) {
                        BiliAccessibilityService.instance.syncFloatingBall();
                    }
                }
        ));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(6), 0, dp(6));

        LinearLayout.LayoutParams leftBtnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        leftBtnParams.rightMargin = dp(8);
        btnRow.addView(createSecondaryButton("复制日志", () -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            String text = TextUtils.join("\n", FilterConfig.recentLogs);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("BiliFilterLogs", text));
            }
            Toast.makeText(MainActivity.this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
        }), leftBtnParams);

        btnRow.addView(createSecondaryButton("清空日志", () -> {
            FilterConfig.recentLogs.clear();
            refreshLogsAndStats();
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        debugCard.addView(btnRow);

        tvLogConsole = new TextView(this);
        tvLogConsole.setBackgroundColor(Color.parseColor("#FF1E1F22"));
        tvLogConsole.setTextColor(Color.parseColor("#FFA9B7C6"));
        tvLogConsole.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvLogConsole.setTypeface(Typeface.MONOSPACE);
        tvLogConsole.setPadding(dp(10), dp(10), dp(10), dp(10));
        tvLogConsole.setMinLines(8);
        debugCard.addView(tvLogConsole);

        root.addView(debugCard);
        scrollView.addView(root);
        return scrollView;
    }

    private void refreshPermissionStatus() {
        boolean isServiceEnabled = isAccessibilityServiceEnabled();
        tvAccessibilityStatus.setText(isServiceEnabled
                ? "✅ 无障碍服务：已激活运行中"
                : "❌ 无障碍服务：未开启（点击下方按钮开启）");
        tvAccessibilityStatus.setTextColor(isServiceEnabled
                ? Color.parseColor("#FF2E7D32")
                : Color.parseColor("#FFD32F2F"));

        boolean canOverlay = Settings.canDrawOverlays(this);
        tvOverlayStatus.setText(canOverlay
                ? "✅ 悬浮窗权限：已授权（可绘制首页遮罩与嗅探球）"
                : "⚠️ 悬浮窗权限：未授权（不影响竖屏火箭推广自动划走与无障碍遮罩层）");
        tvOverlayStatus.setTextColor(canOverlay
                ? Color.parseColor("#FF2E7D32")
                : Color.parseColor("#FFEF6C00"));
    }

    private void refreshLogsAndStats() {
        tvStatsSummary.setText(
                "本次运行统计：已自动划走竖屏推广视频 " + FilterConfig.skippedRocketCount + " 个"
        );

        if (FilterConfig.recentLogs.isEmpty()) {
            tvLogConsole.setText("暂无日志。开启无障碍服务后打开哔哩哔哩即可实时看到检测与拦截记录...");
        } else {
            tvLogConsole.setText(TextUtils.join("\n", FilterConfig.recentLogs));
        }
    }

    private void showEditPromoKeywordsDialog() {
        final EditText input = new EditText(this);
        input.setText(TextUtils.join(", ", FilterConfig.getPromoKeywords()));
        input.setPadding(dp(16), dp(14), dp(16), dp(14));

        new AlertDialog.Builder(this)
                .setTitle("自定义推广识别词（逗号分隔）")
                .setMessage("当视频标签包含以下任一词条时，将自动划走或遮罩屏蔽：")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String raw = input.getText().toString();
                    String[] parts = raw.split("[,，\\n]");
                    Set<String> updated = new LinkedHashSet<>();
                    for (String p : parts) {
                        String trimmed = p.trim();
                        if (!trimmed.isEmpty()) updated.add(trimmed);
                    }
                    if (!updated.isEmpty()) {
                        FilterConfig.setPromoKeywords(updated);
                        FilterConfig.addLog("⚙️ 已更新推广关键词: " + TextUtils.join("/", updated));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean isAccessibilityServiceEnabled() {
        String expectedComponent = getPackageName() + "/" + BiliAccessibilityService.class.getCanonicalName();
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabledServices == null) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            if (splitter.next().equalsIgnoreCase(expectedComponent)) {
                return true;
            }
        }
        return false;
    }

    private LinearLayout createCardLayout() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        return card;
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#FF18191C"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setPadding(0, 0, 0, dp(6));
        return tv;
    }

    private View createSwitchRow(
            String title,
            String subtitle,
            boolean checked,
            final OnToggleListener listener
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.parseColor("#FF222222"));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        tvTitle.setTypeface(tvTitle.getTypeface(), Typeface.BOLD);
        textCol.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText(subtitle);
        tvSub.setTextColor(Color.parseColor("#FF757575"));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvSub.setPadding(0, dp(3), 0, 0);
        textCol.addView(tvSub);

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onToggle(isChecked));

        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw);
        return row;
    }

    private Button createActionButton(String label, final Runnable onClick) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FF00AEEC"));
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private Button createSecondaryButton(String label, final Runnable onClick) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(Color.parseColor("#FFFB7299"));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FFFFF0F5"));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), Color.parseColor("#FFFB7299"));
        btn.setBackground(bg);
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private LinearLayout.LayoutParams marginParams(int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }
}
