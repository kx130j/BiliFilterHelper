package com.antigravity.bilifilter.overlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import com.antigravity.bilifilter.config.FilterConfig;
import java.util.List;

/**
 * 悬浮遮罩管理器（v1.5 实时滑动跟随版）
 *
 * 核心改进：
 * 1. 所有遮罩合并绘制到一个全屏、不可触摸的硬件加速 {@link MaskCanvasView} 窗口中，滑动时零闪烁、零拖影；
 * 2. 优先使用 TYPE_ACCESSIBILITY_OVERLAY（无障碍服务专属窗口），在 Android 12+ 手势完全穿透不拦截；
 * 3. 每个遮罩绑定对应的容器节点（anchorNode）与所属列表视口（clipBounds），滑动时通过实时节点坐标直读 + 滚动增量 + 120Hz 帧外推实现像素级贴合跟随。
 */
public class OverlayMaskManager {

    public static class MaskItemSpec {
        public final Rect bounds;
        public final String userName;
        public final int level;
        public final AccessibilityNodeInfo anchorNode;
        public final Rect clipBounds;

        public MaskItemSpec(Rect bounds, String userName, int level) {
            this(bounds, userName, level, null, null);
        }

        public MaskItemSpec(
                Rect bounds,
                String userName,
                int level,
                AccessibilityNodeInfo anchorNode,
                Rect clipBounds
        ) {
            this.bounds = bounds;
            this.userName = userName;
            this.level = level;
            this.anchorNode = anchorNode;
            this.clipBounds = clipBounds;
        }
    }

    private final Context context;
    private final Runnable onRequestInspectNodes;
    private final WindowManager windowManager;
    private MaskCanvasView canvasView = null;
    private boolean canvasAttached = false;
    private View inspectorBallView = null;

    public OverlayMaskManager(Context context, Runnable onRequestInspectNodes) {
        this.context = context;
        this.onRequestInspectNodes = onRequestInspectNodes;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * 屏幕静止或全量刷新时：根据当前节点真实坐标更新遮罩
     */
    public void updateCommentMasks(List<MaskItemSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            clearCommentMasks();
            return;
        }
        if (!ensureCanvasAttached()) return;
        canvasView.replaceAll(specs);
    }

    /**
     * 滑动途中：保持现有遮罩跟随移动，同时刷新坐标并追加新滑入屏幕的目标
     */
    public void mergeMasksWhileScrolling(List<MaskItemSpec> specs) {
        if (specs == null || specs.isEmpty()) return;
        if (!ensureCanvasAttached()) return;
        canvasView.mergeWhileScrolling(specs);
    }

    /**
     * 收到列表滚动事件时立即同步遮罩位置
     */
    public void onScrollEvent(AccessibilityEvent event) {
        if (canvasView == null || !canvasView.hasMasks()) return;
        canvasView.onScrollEvent(event);
    }

    /**
     * 滚动停止：做最终坐标校准并结束惯性外推
     */
    public void onScrollSettled() {
        if (canvasView != null) canvasView.settleMotion();
    }

    public boolean hasActiveMasks() {
        return canvasView != null && canvasView.hasMasks();
    }

    /**
     * 瞬间隐藏所有遮罩（不触发系统销毁动画，0残影）
     */
    public void clearCommentMasks() {
        if (canvasView != null) canvasView.clearAll();
    }

    private boolean ensureCanvasAttached() {
        if (canvasAttached) return true;
        if (canvasView == null) canvasView = new MaskCanvasView(context);

        if (context instanceof AccessibilityService) {
            try {
                windowManager.addView(canvasView, buildCanvasParams(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY));
                canvasAttached = true;
                return true;
            } catch (Exception ignored) {
            }
        }
        if (Settings.canDrawOverlays(context)) {
            int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            try {
                windowManager.addView(canvasView, buildCanvasParams(overlayType));
                canvasAttached = true;
            } catch (Exception ignored) {
            }
        }
        return canvasAttached;
    }

    private WindowManager.LayoutParams buildCanvasParams(int windowType) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        params.windowAnimations = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return params;
    }

    public void syncInspectorBallVisibility() {
        if (!Settings.canDrawOverlays(context) || !FilterConfig.isEnableInspectorFloatingBall()) {
            removeInspectorBall();
            return;
        }
        if (inspectorBallView != null) return;

        TextView ball = new TextView(context);
        ball.setText("🔍 嗅探节点");
        ball.setTextColor(Color.WHITE);
        ball.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        ball.setPadding(28, 18, 28, 18);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EEFB7299"));
        bg.setCornerRadius(50f);
        ball.setBackground(bg);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 360;
        params.windowAnimations = 0;

        ball.setOnTouchListener(new View.OnTouchListener() {
            private int initialX = 0;
            private int initialY = 0;
            private float touchX = 0f;
            private float touchY = 0f;
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        isDragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchX);
                        int dy = (int) (event.getRawY() - touchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true;
                            params.x = initialX + dx;
                            params.y = initialY + dy;
                            try {
                                windowManager.updateViewLayout(ball, params);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isDragging && onRequestInspectNodes != null) {
                            onRequestInspectNodes.run();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            windowManager.addView(ball, params);
            inspectorBallView = ball;
        } catch (Exception ignored) {
        }
    }

    public void removeInspectorBall() {
        if (inspectorBallView != null) {
            try {
                windowManager.removeView(inspectorBallView);
            } catch (Exception ignored) {
            }
            inspectorBallView = null;
        }
    }

    public void destroyAll() {
        if (canvasView != null && canvasAttached) {
            try {
                windowManager.removeView(canvasView);
            } catch (Exception ignored) {
            }
        }
        canvasAttached = false;
        canvasView = null;
        removeInspectorBall();
    }
}
