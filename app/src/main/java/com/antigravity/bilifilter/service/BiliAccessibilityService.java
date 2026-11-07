package com.antigravity.bilifilter.service;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import com.antigravity.bilifilter.config.FilterConfig;
import com.antigravity.bilifilter.detector.HomeFeedPromoDetector;
import com.antigravity.bilifilter.detector.StoryPromoSkipper;
import com.antigravity.bilifilter.detector.StoryPromoSkipper.StoryPageContext;
import com.antigravity.bilifilter.overlay.OverlayMaskManager;
import java.util.ArrayDeque;
import java.util.List;

/**
 * B站推广视频过滤核心无障碍服务
 *
 * 核心能力：
 * 1. 【竖屏视频（Story模式）双模态极速跳过】：
 *    - 节点树直判：识别左下角推广标签、内联 Span、顶部“广告内容”声明及已确认签名缓存，0ms 立即触发上滑跳过；
 *    - 像素级小火箭识别：支持“播放 ⌄ 🚀”（带简介展开箭头）与“播放 🚀”（无展开箭头直连）双模态识别，
 *      通过“XX播放”节点纵坐标位移门控（|Δtop| <= 14px）在视频落位第一瞬间触发静默截屏秒切；
 * 2. 【首页双列推荐流实时滑动跟随遮罩】：
 *    滑动主页时绝不隐藏遮罩！通过卡片容器节点 anchorNode.refresh() 实时坐标直读 + TYPE_VIEW_SCROLLED 滚动增量 + 120Hz 帧外推，
 *    让遮罩在手指拖动与惯性滑行全过程中紧贴推广卡片移动，并在滑动途中每 95ms 自动为新滑入屏幕的推广卡片追加遮罩。
 */
public class BiliAccessibilityService extends AccessibilityService {

    public static volatile BiliAccessibilityService instance = null;

    private static final int MODE_NONE = 0;
    private static final int MODE_HOME = 1;
    private static final int MODE_STORY = 3;

    /** 系统 takeScreenshot 两次调用最小间隔为 333ms */
    private static final long SCREENSHOT_MIN_INTERVAL_MS = 338L;
    private static final long SCROLL_SETTLE_DELAY_MS = 120L;
    /** 滑动过程中增量扫描新滑入推广卡片的间隔 */
    private static final long SCROLL_MIDWAY_INSPECT_INTERVAL_MS = 95L;
    private static final long EVENT_DEBOUNCE_MS = 18L;
    private static final long PERIODIC_INSPECT_MS = 200L;

    /** 竖屏翻页时快速复测“XX播放”节点是否落位的间隔 */
    private static final long STORY_FAST_RECHECK_MS = 25L;
    private static final int STORY_MAX_SCREENSHOT_CHECKS = 4;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StoryPromoSkipper storyPromoSkipper = new StoryPromoSkipper();
    private final HomeFeedPromoDetector homeFeedPromoDetector = new HomeFeedPromoDetector();
    private OverlayMaskManager overlayMaskManager;

    private int currentMode = MODE_NONE;
    private long lastScreenshotTime = 0L;
    private long lastScrollEventTime = 0L;
    private long lastBigStoryScrollTime = 0L;
    private long lastMidwayInspectTime = 0L;
    private boolean isTakingScreenshot = false;
    private boolean isBiliInForeground = false;

    // 竖屏当前视频落位追踪与复核状态
    private String currentStorySig = "";
    private int lastStoryPlayTop = -1;
    private long lastStoryPlaySampleTime = 0L;
    private int storyScreenshotChecks = 0;

    private long inspectDueAt = 0L;
    private final Runnable inspectRunnable = () -> {
        inspectDueAt = 0L;
        inspectCurrentWindow();
    };

    private final Runnable scrollSettledRunnable = () -> {
        if (overlayMaskManager != null) overlayMaskManager.onScrollSettled();
        inspectCurrentWindow();
    };

    private final Runnable periodicInspectRunnable = new Runnable() {
        @Override
        public void run() {
            if (isBiliInForeground) {
                inspectCurrentWindow();
                mainHandler.postDelayed(this, PERIODIC_INSPECT_MS);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        FilterConfig.init(this);
        instance = this;
        overlayMaskManager = new OverlayMaskManager(this, this::dumpActiveWindowNodesForDebug);
        overlayMaskManager.syncInspectorBallVisibility();
        FilterConfig.addLog("⚡ 无障碍服务已启动（竖屏推广极速跳过 + 首页推广实时跟随遮罩）");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();

        if (pkg.equals(getPackageName())) return;

        boolean isBili = pkg.contains("bili");
        if (!isBili && !pkg.equals("android") && !pkg.contains("systemui")) {
            leaveBiliForeground();
            return;
        }
        if (!isBili) return;

        if (!isBiliInForeground) {
            isBiliInForeground = true;
            mainHandler.removeCallbacks(periodicInspectRunnable);
            mainHandler.postDelayed(periodicInspectRunnable, PERIODIC_INSPECT_MS);
        }

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handleScrollEvent(event);
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            inspectCurrentWindow();
            return;
        }
        scheduleInspect(EVENT_DEBOUNCE_MS);
    }

    /**
     * 处理屏幕滚动事件：
     * 1. 首页推荐流：立即驱动遮罩画布实时刷新卡片坐标跟随滚动，并在滚动途中定期扫描新滑入的推广卡片；
     * 2. 竖屏视频：忽略 1~2px 的减速尾帧，一旦大幅翻页结束立即触发小火箭检测。
     */
    private void handleScrollEvent(AccessibilityEvent event) {
        long now = SystemClock.uptimeMillis();
        lastScrollEventTime = now;

        if (currentMode == MODE_STORY) {
            int dy = 0;
            boolean deltaKnown = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                int dx = event.getScrollDeltaX();
                dy = event.getScrollDeltaY();
                deltaKnown = !(dx == -1 && dy == -1);
            }
            if (!deltaKnown || Math.abs(dy) > 16) {
                lastBigStoryScrollTime = now;
            }
            scheduleInspect(STORY_FAST_RECHECK_MS);
        } else {
            if (overlayMaskManager != null && overlayMaskManager.hasActiveMasks()) {
                overlayMaskManager.onScrollEvent(event);
            }
            if (now - lastMidwayInspectTime >= SCROLL_MIDWAY_INSPECT_INTERVAL_MS) {
                scheduleInspect(12L);
            }
        }

        mainHandler.removeCallbacks(scrollSettledRunnable);
        mainHandler.postDelayed(scrollSettledRunnable, SCROLL_SETTLE_DELAY_MS);
    }

    private void scheduleInspect(long delayMs) {
        long due = SystemClock.uptimeMillis() + delayMs;
        if (inspectDueAt != 0L && inspectDueAt <= due) return;
        mainHandler.removeCallbacks(inspectRunnable);
        inspectDueAt = due;
        mainHandler.postDelayed(inspectRunnable, delayMs);
    }

    private void leaveBiliForeground() {
        isBiliInForeground = false;
        currentMode = MODE_NONE;
        mainHandler.removeCallbacks(periodicInspectRunnable);
        mainHandler.removeCallbacks(inspectRunnable);
        mainHandler.removeCallbacks(scrollSettledRunnable);
        inspectDueAt = 0L;
        if (overlayMaskManager != null) overlayMaskManager.clearCommentMasks();
    }

    private void inspectCurrentWindow() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        CharSequence rootPkg = rootNode.getPackageName();
        if (rootPkg != null && !rootPkg.toString().contains("bili")) {
            if (overlayMaskManager != null) overlayMaskManager.clearCommentMasks();
            return;
        }

        long now = SystemClock.uptimeMillis();
        boolean isScreenScrolling = (now - lastScrollEventTime) < SCROLL_SETTLE_DELAY_MS;

        if (isScreenScrolling && currentMode == MODE_HOME) {
            if (now - lastMidwayInspectTime < SCROLL_MIDWAY_INSPECT_INTERVAL_MS) return;
            lastMidwayInspectTime = now;
        }

        int[] dims = getScreenDimensions();
        int screenWidth = dims[0];
        int screenHeight = dims[1];

        // 1. 竖屏视频（Story模式）：零遮罩 + 极速火箭检测跳过
        StoryPageContext storyCtx = storyPromoSkipper.analyzeStoryNodeTree(rootNode, screenWidth, screenHeight);

        if (storyCtx.isStoryVerticalMode) {
            currentMode = MODE_STORY;
            if (overlayMaskManager != null) overlayMaskManager.clearCommentMasks();
            handleStoryPage(storyCtx, screenWidth, screenHeight);
            return;
        }

        // 2. 首页推荐流模式：实时跟随遮罩 + 滑动途中增量覆盖新滑入的推广卡片
        currentMode = MODE_HOME;
        if (overlayMaskManager == null) return;
        if (FilterConfig.isEnableHomePromoMask()) {
            List<OverlayMaskManager.MaskItemSpec> homeCardMasks =
                    homeFeedPromoDetector.detectPromotedHomeCards(rootNode, screenWidth, screenHeight);
            if (isScreenScrolling && overlayMaskManager.hasActiveMasks()) {
                overlayMaskManager.mergeMasksWhileScrolling(homeCardMasks);
            } else {
                overlayMaskManager.updateCommentMasks(homeCardMasks);
            }
        } else {
            overlayMaskManager.clearCommentMasks();
        }
    }

    /**
     * 竖屏页面处理：
     * 1. 节点树或缓存直接命中 -> 0ms 立即上滑跳过；
     * 2. 通过“XX播放”节点纵坐标位移门控（|Δtop| <= 14px）判定页面已落位，无需傻等滚动尾帧，第一时间触发截屏识别小火箭。
     */
    private void handleStoryPage(StoryPageContext ctx, int screenWidth, int screenHeight) {
        if (!FilterConfig.isEnableRocketPromoSkip()) return;

        long now = SystemClock.uptimeMillis();
        String sig = ctx.videoSignature;
        if (!sig.equals(currentStorySig)) {
            currentStorySig = sig;
            lastStoryPlayTop = ctx.playNodeBounds != null ? ctx.playNodeBounds.top : -1;
            lastStoryPlaySampleTime = now;
            storyScreenshotChecks = 0;
        }

        if (!storyPromoSkipper.canSwipeFor(sig)) {
            scheduleInspect(110L);
            return;
        }

        // A. 节点树或缓存直接命中：0ms 立即划走
        if (ctx.immediateHitReason != null) {
            storyPromoSkipper.executeSkipSwipe(this, screenWidth, screenHeight, ctx.immediateHitReason, sig);
            return;
        }

        // B. 截图像素引擎检测“播放 ⌄ 🚀”或“播放 🚀”
        if (sig.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        if (storyScreenshotChecks >= STORY_MAX_SCREENSHOT_CHECKS) return;

        boolean playNodeSettled = false;
        if (ctx.playNodeBounds != null) {
            long sampleDt = now - lastStoryPlaySampleTime;
            if (sampleDt >= 20L) {
                int dy = lastStoryPlayTop >= 0 ? Math.abs(ctx.playNodeBounds.top - lastStoryPlayTop) : 0;
                lastStoryPlayTop = ctx.playNodeBounds.top;
                lastStoryPlaySampleTime = now;
                playNodeSettled = dy <= 14;
            } else if ((now - lastBigStoryScrollTime) >= 45L) {
                // 视频早已静止，无需再等 25ms 采样直接截屏
                playNodeSettled = true;
            }
        } else {
            playNodeSettled = (now - lastBigStoryScrollTime) >= 85L;
        }

        if (!playNodeSettled) {
            scheduleInspect(STORY_FAST_RECHECK_MS);
            return;
        }

        triggerStoryScreenshotAnalysis(ctx, screenWidth, screenHeight);
    }

    private void triggerStoryScreenshotAnalysis(
            final StoryPageContext storyCtx,
            final int screenWidth,
            final int screenHeight
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        final long requestTime = SystemClock.uptimeMillis();
        long elapsed = requestTime - lastScreenshotTime;

        if (isTakingScreenshot || elapsed < SCREENSHOT_MIN_INTERVAL_MS) {
            long delay = isTakingScreenshot ? 45L : Math.max(16L, SCREENSHOT_MIN_INTERVAL_MS - elapsed + 6L);
            scheduleInspect(delay);
            return;
        }

        isTakingScreenshot = true;
        lastScreenshotTime = requestTime;

        takeScreenshot(
                Display.DEFAULT_DISPLAY,
                getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshotResult) {
                        isTakingScreenshot = false;
                        HardwareBuffer hardwareBuffer = screenshotResult.getHardwareBuffer();
                        if (hardwareBuffer == null) return;
                        try {
                            ColorSpace colorSpace = screenshotResult.getColorSpace() != null
                                    ? screenshotResult.getColorSpace()
                                    : ColorSpace.get(ColorSpace.Named.SRGB);
                            Bitmap hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace);
                            Bitmap softBitmap = hwBitmap != null
                                    ? hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    : null;
                            if (hwBitmap != null) hwBitmap.recycle();
                            hardwareBuffer.close();

                            if (softBitmap == null) return;

                            boolean hasRocket = storyPromoSkipper.detectRocketInStoryBottomZoneFromBitmap(
                                    softBitmap,
                                    storyCtx.playNodeBounds,
                                    storyCtx.playNodeIsPlayCount
                            );
                            softBitmap.recycle();
                            onStoryScreenshotResult(storyCtx, hasRocket, screenWidth, screenHeight);
                        } catch (Exception ignored) {
                            try {
                                hardwareBuffer.close();
                            } catch (Exception ignored2) {
                            }
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        isTakingScreenshot = false;
                        scheduleInspect(errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                                ? SCREENSHOT_MIN_INTERVAL_MS : 90L);
                    }
                }
        );
    }

    private void onStoryScreenshotResult(
            StoryPageContext shotCtx,
            boolean hasRocket,
            int screenWidth,
            int screenHeight
    ) {
        String sig = shotCtx.videoSignature;
        if (hasRocket) {
            storyPromoSkipper.markConfirmedPromo(sig);
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                StoryPageContext nowCtx = storyPromoSkipper.analyzeStoryNodeTree(root, screenWidth, screenHeight);
                // 若截屏期间用户已经手动滑到了另一个不同签名的视频，则不对新视频误滑
                if (nowCtx.isStoryVerticalMode
                        && !nowCtx.videoSignature.isEmpty()
                        && !sig.equals(nowCtx.videoSignature)) {
                    scheduleInspect(STORY_FAST_RECHECK_MS);
                    return;
                }
            }
            storyPromoSkipper.executeSkipSwipe(
                    this,
                    screenWidth,
                    screenHeight,
                    "播放行小火箭(" + sig + ")",
                    sig
            );
            return;
        }

        if (sig.equals(currentStorySig)) {
            storyScreenshotChecks++;
            if (storyScreenshotChecks < STORY_MAX_SCREENSHOT_CHECKS) {
                scheduleInspect(180L);
            }
        }
    }

    public void dumpActiveWindowNodesForDebug() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Toast.makeText(this, "当前未获取到活动窗口节点", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        Rect rect = new Rect();
        int count = 0;

        FilterConfig.addLog("====== 🔍 开始抓取当前屏幕无障碍节点 ======");
        while (!queue.isEmpty() && count < 60) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isVisibleToUser()) {
                String fullId = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
                int slashIdx = fullId.lastIndexOf('/');
                String id = slashIdx >= 0 ? fullId.substring(slashIdx + 1) : fullId;
                String text = node.getText() != null ? node.getText().toString().trim() : "";
                String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim() : "";
                if (!id.isEmpty() || !text.isEmpty() || !desc.isEmpty()) {
                    node.getBoundsInScreen(rect);
                    FilterConfig.addLog(
                            "节点[" + id + "] txt=\"" + text + "\" desc=\"" + desc + "\" rect=("
                                    + rect.left + "," + rect.top + "-" + rect.right + "," + rect.bottom + ")"
                    );
                    count++;
                }
            }
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        Toast.makeText(this, "已抓取 " + count + " 个关键节点到运行日志", Toast.LENGTH_SHORT).show();
    }

    public void syncFloatingBall() {
        if (overlayMaskManager != null) {
            mainHandler.post(() -> overlayMaskManager.syncInspectorBallVisibility());
        }
    }

    private int[] getScreenDimensions() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        return new int[]{metrics.widthPixels, metrics.heightPixels};
    }

    @Override
    public void onInterrupt() {
        mainHandler.removeCallbacks(periodicInspectRunnable);
        mainHandler.removeCallbacks(inspectRunnable);
        mainHandler.removeCallbacks(scrollSettledRunnable);
        inspectDueAt = 0L;
        if (overlayMaskManager != null) {
            overlayMaskManager.clearCommentMasks();
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        mainHandler.removeCallbacks(periodicInspectRunnable);
        mainHandler.removeCallbacks(inspectRunnable);
        mainHandler.removeCallbacks(scrollSettledRunnable);
        if (overlayMaskManager != null) {
            overlayMaskManager.destroyAll();
        }
        super.onDestroy();
    }
}
