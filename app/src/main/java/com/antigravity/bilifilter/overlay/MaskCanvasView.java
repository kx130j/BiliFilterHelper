package com.antigravity.bilifilter.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 全屏透明硬件加速遮罩画布（v1.5 实时滑动跟随版）
 *
 * 三重实时滑动跟随引擎：
 * 1. 【视图节点实时坐标直读（anchorNode.refresh）】：
 *    由于 B 站首页与评论区均采用 RecyclerView（通过 offsetChildrenVertical 平移子 View，mScrollY 常为 0），
 *    画布在 VSYNC 帧回调中直接对被遮罩卡片/评论的容器节点调用 refresh() + getBoundsInScreen()，
 *    毫秒级读取子 View 在屏幕上的真实像素坐标，哪怕手指慢速拖动 1px 也能零延迟紧贴移动；
 * 2. 【滚动事件位移增量（getScrollDeltaY / getScrollY）】：
 *    当收到 TYPE_VIEW_SCROLLED 事件时立即刷新节点真实位置，若部分边缘节点无法刷新则按滚动增量同步平移；
 * 3. 【120Hz VSYNC 速度外推 + 容器视口裁剪（clipBounds）】：
 *    在两次 IPC 采样之间按实测滚动速度做亚帧线性外推，并将遮罩严格裁剪在所属 RecyclerView 可视区域内，
 *    滑动到顶部标签栏或底部导航栏/评论输入框边缘时自动平滑裁切，绝不越界遮挡。
 */
final class MaskCanvasView extends View implements Choreographer.FrameCallback {

    private static final int MAX_MASKS = 12;
    /** 激活态下两次 IPC refresh() 的最小间隔（毫秒），兼顾 90/120Hz 流畅度与低开销 */
    private static final long ACTIVE_REFRESH_INTERVAL_MS = 10L;
    /** 静止态下探测手指刚按下微移的巡检间隔（毫秒） */
    private static final long IDLE_PROBE_INTERVAL_MS = 32L;
    /** 速度外推最大时长（毫秒） */
    private static final long MAX_EXTRAPOLATION_MS = 55L;
    /** 连续无位移多久后从激活跟随态回到静止态（毫秒） */
    private static final long MOTION_IDLE_TIMEOUT_MS = 180L;
    private static final float MAX_VELOCITY_PX_PER_MS = 10f;

    static final class TrackedMask {
        /** 最近一次确认的屏幕真实坐标 */
        final RectF trueRect = new RectF();
        /** 最近一次确认坐标的时间戳（uptimeMillis） */
        long trueTime;
        /** 绑定的无障碍容器节点（用于 refresh() 实时读取屏幕坐标） */
        AccessibilityNodeInfo anchorNode;
        /** 所属滚动列表容器的视口边界（用于上下边缘裁剪） */
        Rect clip;
        String key;
        String[] lines;
        /** 连续多少次 refresh() 失败（用于判断节点是否已滑出屏幕被 RecyclerView 回收） */
        int refreshFailCount = 0;
    }

    private final List<TrackedMask> masks = new ArrayList<>();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float cornerRadius;
    private final int[] windowLocation = new int[2];
    private final Rect tempRect = new Rect();
    private final RectF drawRect = new RectF();
    private final RectF clipRectF = new RectF();

    // 滚动运动估计（屏幕坐标系，px/ms，手指上滑时内容向上移动，velY < 0）
    private float velX = 0f;
    private float velY = 0f;
    private long lastMovementTime = 0L;
    private long lastRefreshPollTime = 0L;
    private boolean motionActive = false;
    private boolean frameScheduled = false;

    // 兼容 ScrollView / NestedScrollView 的 scrollY 差值跟踪
    private int lastEventWindowId = -1;
    private int lastEventScrollX = -1;
    private int lastEventScrollY = -1;
    private long lastEventScrollTime = 0L;

    MaskCanvasView(Context context) {
        super(context);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.parseColor("#FA222428"));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(context, 1.2f));
        strokePaint.setColor(Color.parseColor("#55FB7299"));
        textPaint.setColor(Color.parseColor("#FF99A2AA"));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 12f, context.getResources().getDisplayMetrics()));
        cornerRadius = dp(context, 8f);
        setVisibility(GONE);
    }

    private static float dp(Context context, float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    boolean hasMasks() {
        return !masks.isEmpty();
    }

    /**
     * 屏幕静止或全量刷新时：用最新检测到的列表替换遮罩，并保留已存在遮罩的平滑运动状态
     */
    void replaceAll(List<OverlayMaskManager.MaskItemSpec> specs) {
        long now = SystemClock.uptimeMillis();
        masks.clear();
        if (specs != null) {
            for (OverlayMaskManager.MaskItemSpec spec : specs) {
                if (masks.size() >= MAX_MASKS) break;
                masks.add(createMask(spec, now));
            }
        }
        lastRefreshPollTime = now;
        refreshVisibility();
        if (!masks.isEmpty()) {
            scheduleFrame();
        }
        invalidate();
    }

    /**
     * 滑动途中增量扫描：
     * 1. 已存在的遮罩如果匹配到新扫描出的节点，直接更新其 anchorNode 和最新真实坐标；
     * 2. 新滑入视野的推广卡片/低等级评论立即追加遮罩，无需等屏幕停稳。
     */
    void mergeWhileScrolling(List<OverlayMaskManager.MaskItemSpec> specs) {
        if (specs == null) return;
        long now = SystemClock.uptimeMillis();

        // 先刷新一遍现有遮罩的真实位置，确保匹配基准准确
        pollLiveAnchors(now, true);

        boolean changed = false;
        for (OverlayMaskManager.MaskItemSpec spec : specs) {
            String key = keyOf(spec);
            TrackedMask matched = null;
            float bestDist = Float.MAX_VALUE;
            for (TrackedMask m : masks) {
                if (!m.key.equals(key)) continue;
                float dy = Math.abs(m.trueRect.centerY() - spec.bounds.centerY());
                float dx = Math.abs(m.trueRect.centerX() - spec.bounds.centerX());
                if (dx < spec.bounds.width() * 0.45f && dy < 220f && dy < bestDist) {
                    bestDist = dy;
                    matched = m;
                }
            }
            if (matched != null) {
                float stepDy = spec.bounds.top - matched.trueRect.top;
                float stepDx = spec.bounds.left - matched.trueRect.left;
                long dt = Math.max(8L, now - matched.trueTime);
                if (Math.abs(stepDy) > 0.5f || Math.abs(stepDx) > 0.5f) {
                    updateVelocity(stepDx / dt, stepDy / dt, now);
                }
                matched.trueRect.set(spec.bounds);
                matched.trueTime = now;
                if (spec.anchorNode != null) {
                    matched.anchorNode = spec.anchorNode;
                    matched.refreshFailCount = 0;
                }
                if (spec.clipBounds != null && !spec.clipBounds.isEmpty()) {
                    matched.clip = new Rect(spec.clipBounds);
                }
                changed = true;
            } else if (masks.size() < MAX_MASKS) {
                masks.add(createMask(spec, now));
                changed = true;
            }
        }

        changed |= pruneOffscreenMasks();
        if (changed) {
            refreshVisibility();
            scheduleFrame();
            invalidate();
        }
    }

    /**
     * 收到 TYPE_VIEW_SCROLLED 滚动事件时立即同步遮罩位置
     */
    void onScrollEvent(AccessibilityEvent event) {
        if (masks.isEmpty()) return;
        long now = SystemClock.uptimeMillis();

        // 1. 优先通过 anchorNode.refresh() 直接获取各遮罩子 View 在屏幕上的最新像素位置
        int refreshedCount = pollLiveAnchors(now, true);

        // 2. 提取 AccessibilityEvent 中的滚动增量作为补充（针对无 anchorNode 或尚未刷新的遮罩）
        int eventDx = extractScrollDeltaX(event, now);
        int eventDy = extractScrollDeltaY(event, now);

        if (refreshedCount == 0 && (eventDx != 0 || eventDy != 0)) {
            long dt = Math.max(8L, now - lastMovementTime);
            updateVelocity(-eventDx / (float) dt, -eventDy / (float) dt, now);
            for (TrackedMask m : masks) {
                m.trueRect.offset(-eventDx, -eventDy);
                m.trueTime = now;
            }
        } else if (refreshedCount == 0) {
            // 即使本次事件未携带非零 delta，也立刻激活高频帧监听以便下一帧捕捉位移
            motionActive = true;
            lastMovementTime = now;
        }

        pruneOffscreenMasks();
        refreshVisibility();
        scheduleFrame();
        invalidate();
    }

    /**
     * 滚动停止通知：做最后一次精确位置刷新并归零外推速度
     */
    void settleMotion() {
        if (masks.isEmpty()) {
            resetMotion();
            return;
        }
        long now = SystemClock.uptimeMillis();
        pollLiveAnchors(now, true);
        velX = 0f;
        velY = 0f;
        motionActive = false;
        pruneOffscreenMasks();
        refreshVisibility();
        invalidate();
    }

    void clearAll() {
        if (masks.isEmpty() && getVisibility() == GONE) return;
        masks.clear();
        resetMotion();
        refreshVisibility();
        invalidate();
    }

    /**
     * 核心：直接调用每个遮罩绑定的 AccessibilityNodeInfo.refresh() 读取实时屏幕坐标。
     * 若某个遮罩节点刷新成功并测得位移，自动将该位移共享给同列表中未能刷新的兄弟遮罩。
     *
     * @return 成功通过 refresh() 更新坐标的遮罩数量
     */
    private int pollLiveAnchors(long now, boolean force) {
        if (masks.isEmpty()) return 0;
        long minInterval = motionActive ? ACTIVE_REFRESH_INTERVAL_MS : IDLE_PROBE_INTERVAL_MS;
        if (!force && (now - lastRefreshPollTime) < minInterval) {
            return 0;
        }
        lastRefreshPollTime = now;

        int refreshedCount = 0;
        float sharedStepDx = 0f;
        float sharedStepDy = 0f;
        long sharedDt = 16L;
        boolean hasMeasuredShift = false;

        List<TrackedMask> unrefreshed = null;

        Iterator<TrackedMask> it = masks.iterator();
        while (it.hasNext()) {
            TrackedMask m = it.next();
            boolean ok = false;
            if (m.anchorNode != null) {
                try {
                    if (m.anchorNode.refresh() && m.anchorNode.isVisibleToUser()) {
                        m.anchorNode.getBoundsInScreen(tempRect);
                        if (tempRect.width() >= 24 && tempRect.height() >= 18) {
                            ok = true;
                        }
                    }
                } catch (Exception ignored) {
                    ok = false;
                }
            }

            if (ok) {
                m.refreshFailCount = 0;
                refreshedCount++;

                // 计算子 View 顶边/底边的真实位移（注意：当卡片在列表顶部被裁剪时 top 不变而 bottom 变化，反之亦然）
                float dTop = tempRect.top - m.trueRect.top;
                float dBot = tempRect.bottom - m.trueRect.bottom;
                float stepDy = Math.abs(dTop) >= Math.abs(dBot) ? dTop : dBot;
                float stepDx = tempRect.left - m.trueRect.left;
                long dt = Math.max(6L, now - m.trueTime);

                m.trueRect.set(tempRect);
                m.trueTime = now;

                if (Math.abs(stepDy) >= 0.8f || Math.abs(stepDx) >= 0.8f) {
                    sharedStepDx = stepDx;
                    sharedStepDy = stepDy;
                    sharedDt = dt;
                    hasMeasuredShift = true;
                }
            } else {
                m.refreshFailCount++;
                // 如果节点已被 RecyclerView 回收（连续 2 次 refresh 失败），立刻移除该遮罩防残留
                if (m.anchorNode != null && m.refreshFailCount >= 2) {
                    it.remove();
                    continue;
                }
                if (unrefreshed == null) unrefreshed = new ArrayList<>();
                unrefreshed.add(m);
            }
        }

        if (hasMeasuredShift) {
            updateVelocity(sharedStepDx / sharedDt, sharedStepDy / sharedDt, now);
            if (unrefreshed != null) {
                for (TrackedMask m : unrefreshed) {
                    m.trueRect.offset(sharedStepDx, sharedStepDy);
                    m.trueTime = now;
                }
            }
        } else if (motionActive && (now - lastMovementTime) > MOTION_IDLE_TIMEOUT_MS) {
            velX = 0f;
            velY = 0f;
            motionActive = false;
        }

        return refreshedCount;
    }

    private void updateVelocity(float vx, float vy, long now) {
        float clampedVx = clampVelocity(vx);
        float clampedVy = clampVelocity(vy);
        if (!motionActive || (now - lastMovementTime) > MOTION_IDLE_TIMEOUT_MS) {
            velX = clampedVx;
            velY = clampedVy;
        } else {
            velX = 0.70f * clampedVx + 0.30f * velX;
            velY = 0.70f * clampedVy + 0.30f * velY;
        }
        motionActive = true;
        lastMovementTime = now;
    }

    private int extractScrollDeltaX(AccessibilityEvent event, long now) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            int dx = event.getScrollDeltaX();
            int dy = event.getScrollDeltaY();
            if (!(dx == -1 && dy == -1) && dx != 0) {
                return dx;
            }
        }
        int sx = event.getScrollX();
        int winId = event.getWindowId();
        int delta = 0;
        if (sx >= 0 && winId == lastEventWindowId && lastEventScrollX >= 0 && (now - lastEventScrollTime) < 400L) {
            delta = sx - lastEventScrollX;
        }
        if (sx >= 0) {
            lastEventScrollX = sx;
        }
        return delta;
    }

    private int extractScrollDeltaY(AccessibilityEvent event, long now) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            int dx = event.getScrollDeltaX();
            int dy = event.getScrollDeltaY();
            if (!(dx == -1 && dy == -1) && dy != 0) {
                return dy;
            }
        }
        int sy = event.getScrollY();
        int winId = event.getWindowId();
        int delta = 0;
        if (sy >= 0 && winId == lastEventWindowId && lastEventScrollY >= 0 && (now - lastEventScrollTime) < 400L) {
            delta = sy - lastEventScrollY;
        }
        lastEventWindowId = winId;
        if (sy >= 0) {
            lastEventScrollY = sy;
        }
        lastEventScrollTime = now;
        return delta;
    }

    private void resetMotion() {
        velX = 0f;
        velY = 0f;
        motionActive = false;
        lastEventWindowId = -1;
        lastEventScrollX = -1;
        lastEventScrollY = -1;
    }

    private TrackedMask createMask(OverlayMaskManager.MaskItemSpec spec, long now) {
        TrackedMask m = new TrackedMask();
        m.trueRect.set(spec.bounds);
        m.trueTime = now;
        m.anchorNode = spec.anchorNode;
        m.clip = (spec.clipBounds != null && !spec.clipBounds.isEmpty()) ? new Rect(spec.clipBounds) : null;
        m.key = keyOf(spec);
        m.lines = new String[]{"🚀 已屏蔽推广视频", "(" + spec.userName + ")"};
        return m;
    }

    private static String keyOf(OverlayMaskManager.MaskItemSpec spec) {
        return spec.level + "|" + spec.userName;
    }

    private boolean pruneOffscreenMasks() {
        int h = getResources().getDisplayMetrics().heightPixels;
        int w = getResources().getDisplayMetrics().widthPixels;
        boolean removed = false;
        Iterator<TrackedMask> it = masks.iterator();
        while (it.hasNext()) {
            TrackedMask m = it.next();
            if (m.clip != null) {
                if (m.trueRect.bottom <= m.clip.top + 4 || m.trueRect.top >= m.clip.bottom - 4) {
                    it.remove();
                    removed = true;
                    continue;
                }
            }
            if (m.trueRect.bottom < 0 || m.trueRect.top > h || m.trueRect.right < 0 || m.trueRect.left > w) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    private float clampVelocity(float v) {
        return Math.max(-MAX_VELOCITY_PX_PER_MS, Math.min(MAX_VELOCITY_PX_PER_MS, v));
    }

    private void refreshVisibility() {
        int target = masks.isEmpty() ? GONE : VISIBLE;
        if (getVisibility() != target) setVisibility(target);
    }

    private void scheduleFrame() {
        if (frameScheduled) return;
        frameScheduled = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        frameScheduled = false;
        if (masks.isEmpty() || getVisibility() != VISIBLE) return;

        long now = SystemClock.uptimeMillis();
        int prevSize = masks.size();
        int refreshed = pollLiveAnchors(now, false);
        boolean pruned = pruneOffscreenMasks();

        if (masks.isEmpty()) {
            refreshVisibility();
            invalidate();
            return;
        }

        if (motionActive || refreshed > 0 || pruned || masks.size() != prevSize) {
            invalidate();
        }

        // 只要屏幕上还有遮罩，保持轻量级或高频 VSYNC 帧驱动（静止时 32ms 探测一次手指起滑，滚动时 10ms 逐帧紧跟）
        scheduleFrame();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Choreographer.getInstance().removeFrameCallback(this);
        frameScheduled = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (masks.isEmpty()) return;
        getLocationOnScreen(windowLocation);
        long now = SystemClock.uptimeMillis();
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float lineHeight = fm.descent - fm.ascent;

        for (TrackedMask m : masks) {
            drawRect.set(m.trueRect);
            if (motionActive) {
                float dt = Math.max(0f, Math.min(now - m.trueTime, MAX_EXTRAPOLATION_MS));
                drawRect.offset(velX * dt, velY * dt);
            }
            if (m.clip != null) {
                clipRectF.set(m.clip);
                if (!drawRect.intersect(clipRectF)) continue;
            }
            if (drawRect.height() < 12f || drawRect.width() < 24f) continue;
            drawRect.offset(-windowLocation[0], -windowLocation[1]);

            canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, fillPaint);
            canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, strokePaint);

            if (drawRect.height() >= lineHeight * m.lines.length + 8f) {
                canvas.save();
                canvas.clipRect(drawRect);
                float totalH = lineHeight * m.lines.length;
                float y = drawRect.centerY() - totalH / 2f - fm.ascent;
                for (String line : m.lines) {
                    canvas.drawText(line, drawRect.centerX(), y, textPaint);
                    y += lineHeight;
                }
                canvas.restore();
            }
        }
    }
}
