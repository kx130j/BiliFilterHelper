package com.antigravity.bilifilter.detector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.util.LruCache;
import android.view.accessibility.AccessibilityNodeInfo;
import com.antigravity.bilifilter.config.FilterConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 竖屏视频（Story模式）“火箭标 / 创作推广”检测与自动上滑跳过器（v1.6 双模火箭全兼容极速版）
 *
 * 核心升级：
 * 1. 【兼容“有展开箭头（播放 ⌄ 🚀）”与“无展开箭头（播放 🚀）”双模式】：
 *    部分推广视频无简介展开箭头（如“你来你也过不了第一关！ 118.2万播放 🚀”），像素引擎同时识别：
 *    - 模式 A：[放] + [展开箭头 ⌄] + [小火箭 🚀]
 *    - 模式 B：[播] + [放] + [小火箭 🚀]（无展开箭头直连）
 * 2. 【扫描宽度扩展至 78% 屏宽】：防止单行长标题将“XX万播放 🚀”推向右侧（如 x=305..318）时被裁剪截断；
 * 3. 【移除易误滤的 Pager 子容器裁剪】：直接按屏幕真实高度区间（60%~95%）定位最靠近 88% 基准线的“XX播放”节点；
 * 4. 【新增顶部游戏广告免责声明直判】：遇到“广告内容含创意元素”等游戏推广节点 0ms 直接划走。
 */
public class StoryPromoSkipper {

    private static final long SWIPE_COOLDOWN_MS = 380L;
    private static final long SAME_VIDEO_RETRY_MS = 1200L;

    private long lastSwipeTimestamp = 0L;
    private String lastSkippedSignature = "";
    private String lastLoggedVideoSignature = "";

    private final LruCache<String, Boolean> promoResultCache = new LruCache<>(120);

    private static final Pattern PLAY_COUNT_PATTERN =
            Pattern.compile("([0-9][0-9.,]*\\s*[万亿]?\\s*(?:次)?播放)");

    private static final List<String> KNOWN_PROMO_VIEW_ID_HINTS = Arrays.asList(
            "story_ad",
            "story_promo",
            "creativity_promo",
            "rocket_tag",
            "commercial_tag",
            "promo_tag",
            "ad_tag"
    );

    private static final List<String> STORY_ACTION_LABELS = Arrays.asList(
            "立即下载",
            "去看看",
            "查看详情",
            "立即购买",
            "领券购买"
    );

    public static class StoryPageContext {
        public final boolean isStoryVerticalMode;
        public final String videoSignature;
        public final Rect playNodeBounds;
        public final boolean playNodeIsPlayCount;
        public final String immediateHitReason;

        public StoryPageContext(
                boolean isStoryVerticalMode,
                String videoSignature,
                Rect playNodeBounds,
                boolean playNodeIsPlayCount,
                String immediateHitReason
        ) {
            this.isStoryVerticalMode = isStoryVerticalMode;
            this.videoSignature = videoSignature;
            this.playNodeBounds = playNodeBounds;
            this.playNodeIsPlayCount = playNodeIsPlayCount;
            this.immediateHitReason = immediateHitReason;
        }

        static StoryPageContext empty() {
            return new StoryPageContext(false, "", null, false, null);
        }
    }

    public boolean canSwipeFor(String videoSignature) {
        long since = SystemClock.uptimeMillis() - lastSwipeTimestamp;
        if (since < SWIPE_COOLDOWN_MS) return false;
        return videoSignature == null
                || videoSignature.isEmpty()
                || !videoSignature.equals(lastSkippedSignature)
                || since >= SAME_VIDEO_RETRY_MS;
    }

    public void markConfirmedPromo(String videoSignature) {
        if (videoSignature != null && !videoSignature.isEmpty()) {
            promoResultCache.put(videoSignature, Boolean.TRUE);
        }
    }

    public StoryPageContext analyzeStoryNodeTree(
            AccessibilityNodeInfo root,
            int screenWidth,
            int screenHeight
    ) {
        if (root == null) return StoryPageContext.empty();

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        Rect rect = new Rect();
        Set<String> keywords = FilterConfig.getPromoKeywords();

        boolean hasExplicitStoryText = false;
        boolean hasRightSideActionColumn = false;
        boolean hasBottomLeftAuthorOrPlay = false;
        boolean hasHomeBottomNav = false;

        CharSequence bestPlayRawCs = null;
        String bestPlayLabel = "";
        Rect bestPlayBounds = null;
        boolean bestPlayIsCount = false;

        String fallbackTitle = "";
        Rect fallbackBounds = null;
        String authorOrFanAnchor = "";
        String immediateHit = null;

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            if (node.isVisibleToUser()) {
                node.getBoundsInScreen(rect);
                String viewId = node.getViewIdResourceName() != null
                        ? node.getViewIdResourceName().toLowerCase() : "";
                String shortId = viewId.contains("/")
                        ? viewId.substring(viewId.lastIndexOf('/') + 1) : viewId;
                CharSequence rawCs = node.getText();
                String text = rawCs != null ? rawCs.toString().trim() : "";
                String desc = node.getContentDescription() != null
                        ? node.getContentDescription().toString().trim() : "";
                String hint = node.getHintText() != null
                        ? node.getHintText().toString().trim() : "";
                String label = !text.isEmpty() ? text : desc;

                if (rect.top > screenHeight * 0.88f
                        && ("首页".equals(text) || "我的".equals(text) || "会员购".equals(text))) {
                    hasHomeBottomNav = true;
                }

                if (text.contains("正在看") || text.contains("发弹幕") || text.equals("详情页")
                        || desc.contains("正在看") || desc.contains("发弹幕")
                        || hint.contains("弹幕") || viewId.contains("story_")) {
                    hasExplicitStoryText = true;
                }

                if (rect.left > screenWidth * 0.78f && rect.top > screenHeight * 0.42f
                        && ("投币".equals(text) || "收藏".equals(text) || "分享".equals(text) || "评论".equals(text)
                        || desc.contains("点赞") || desc.contains("评论") || desc.contains("收藏"))) {
                    hasRightSideActionColumn = true;
                }

                if (rect.left < screenWidth * 0.68f && rect.top > screenHeight * 0.60f
                        && (text.contains("粉丝") || text.contains("播放") || "+ 关注".equals(text) || "关注".equals(text))) {
                    hasBottomLeftAuthorOrPlay = true;
                }

                // 顶部商业游戏广告警示语直判（如“广告内容含创意元素，实际画面以游戏内显示为准”）
                if (immediateHit == null && rect.top > screenHeight * 0.04f && rect.bottom < screenHeight * 0.30f) {
                    if (label.contains("广告内容") || label.contains("实际画面以")) {
                        immediateHit = "顶部广告声明";
                    }
                }

                // 提取左下角粉丝数或 UP 主名作为辅助锚点
                if (rect.top > screenHeight * 0.64f && rect.bottom < screenHeight * 0.90f && rect.left < screenWidth * 0.50f) {
                    if (text.endsWith("粉丝") && text.length() <= 14) {
                        authorOrFanAnchor = text;
                    } else if (authorOrFanAnchor.isEmpty()
                            && text.length() >= 2 && text.length() <= 16
                            && !text.contains("播放") && !text.contains("关注")
                            && !text.contains("弹幕") && !text.contains("正在看")
                            && rect.height() < screenHeight * 0.048f) {
                        authorOrFanAnchor = text;
                    }
                }

                // 寻找当前屏幕下方（60%~95%）的“XX播放”节点，优先选择含数字播放量且最靠近 88% 基准高度的节点
                if (rect.top > screenHeight * 0.60f && rect.bottom < screenHeight * 0.95f && rect.left < screenWidth * 0.78f) {
                    if (label.contains("播放")) {
                        boolean isCount = PLAY_COUNT_PATTERN.matcher(label).find();
                        boolean better;
                        if (bestPlayBounds == null) {
                            better = true;
                        } else if (isCount != bestPlayIsCount) {
                            better = isCount;
                        } else {
                            better = Math.abs(rect.bottom - screenHeight * 0.88f)
                                    < Math.abs(bestPlayBounds.bottom - screenHeight * 0.88f);
                        }
                        if (better) {
                            bestPlayRawCs = rawCs;
                            bestPlayLabel = label;
                            bestPlayBounds = new Rect(rect);
                            bestPlayIsCount = isCount;
                        }
                    } else if (fallbackBounds == null && text.length() >= 4 && rect.top > screenHeight * 0.74f) {
                        fallbackTitle = text;
                        fallbackBounds = new Rect(rect);
                    }
                }

                // 检查左下角推广控件 ID 与推广标签文案
                if (immediateHit == null && rect.top > screenHeight * 0.50f && rect.bottom < screenHeight * 0.95f) {
                    if (!viewId.isEmpty()) {
                        for (String hintStr : KNOWN_PROMO_VIEW_ID_HINTS) {
                            if (viewId.contains(hintStr) && rect.width() > 8 && rect.height() > 8) {
                                immediateHit = "控件ID:" + shortId;
                                break;
                            }
                        }
                    }
                    if (immediateHit == null && rect.height() >= 10 && rect.height() <= (screenHeight / 12)
                            && rect.left < screenWidth * 0.68f && !label.isEmpty() && label.length() <= 8) {
                        for (String act : STORY_ACTION_LABELS) {
                            if (act.equals(label)) {
                                immediateHit = "推广按钮:\"" + label + "\"";
                                break;
                            }
                        }
                        if (immediateHit == null) {
                            for (String kw : keywords) {
                                if ("推广".equals(kw)) {
                                    if ("推广".equals(label) || "创作推广".equals(label)
                                            || "商业推广".equals(label) || "花火推广".equals(label)
                                            || "起飞推广".equals(label) || "品牌推广".equals(label)
                                            || label.contains("火箭")) {
                                        immediateHit = "标签:\"" + label + "\"";
                                        break;
                                    }
                                } else if (label.equals(kw)) {
                                    immediateHit = "标签:\"" + label + "\"";
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }

        boolean isStoryMode = !hasHomeBottomNav
                && (hasExplicitStoryText || (hasRightSideActionColumn && hasBottomLeftAuthorOrPlay));
        if (!isStoryMode) {
            return StoryPageContext.empty();
        }

        String canonicalPlay = "";
        Rect playNodeBounds = null;

        if (bestPlayBounds != null) {
            Matcher m = PLAY_COUNT_PATTERN.matcher(bestPlayLabel);
            if (m.find()) {
                canonicalPlay = m.group(1).replaceAll("\\s+", "");
                int matchStart = m.start();
                if (matchStart > 0) {
                    String prefix = bestPlayLabel.substring(0, matchStart).trim();
                    if (prefix.length() > 12) prefix = prefix.substring(0, 12);
                    canonicalPlay = prefix + "|" + canonicalPlay;
                }
            } else {
                canonicalPlay = bestPlayLabel.length() > 18 ? bestPlayLabel.substring(0, 18) : bestPlayLabel;
            }
            playNodeBounds = bestPlayBounds;

            if (immediateHit == null && bestPlayRawCs != null) {
                String rawStr = bestPlayRawCs.toString();
                int idx = rawStr.indexOf("播放");
                if (idx >= 0) {
                    String suffix = rawStr.substring(idx + 2);
                    int objChars = 0;
                    for (int i = 0; i < suffix.length(); i++) {
                        if (suffix.charAt(i) == '\uFFFC') objChars++;
                    }
                    if (objChars >= 2) {
                        immediateHit = "播放行双内联图标(\\uFFFC×" + objChars + ")";
                    } else if (bestPlayRawCs instanceof Spanned) {
                        Spanned spanned = (Spanned) bestPlayRawCs;
                        ReplacementSpan[] spans = spanned.getSpans(idx + 2, rawStr.length(), ReplacementSpan.class);
                        if (spans != null && spans.length >= 2) {
                            immediateHit = "播放行双ReplacementSpan(×" + spans.length + ")";
                        }
                    }
                }
            }
        } else if (fallbackBounds != null) {
            canonicalPlay = fallbackTitle.length() > 16
                    ? fallbackTitle.substring(0, 16)
                    : fallbackTitle;
            playNodeBounds = fallbackBounds;
        }

        String signature = canonicalPlay.isEmpty()
                ? ""
                : (authorOrFanAnchor.isEmpty() ? canonicalPlay : authorOrFanAnchor + "|" + canonicalPlay);

        if (immediateHit == null && !signature.isEmpty()) {
            Boolean cached = promoResultCache.get(signature);
            if (Boolean.TRUE.equals(cached)) {
                immediateHit = "缓存秒判(" + signature + ")";
            }
        }

        if (!signature.isEmpty() && !signature.equals(lastLoggedVideoSignature)) {
            lastLoggedVideoSignature = signature;
            FilterConfig.addLog("🎬 竖屏当前视频: \"" + signature + "\"");
        }

        return new StoryPageContext(
                true,
                signature,
                playNodeBounds,
                bestPlayBounds != null && bestPlayIsCount,
                immediateHit
        );
    }

    /**
     * 高精度无彩度白灰滤波 + 双模式火箭检测器：
     * - 模式 A（有展开箭头）：[放] -> [⌄] -> [🚀]
     * - 模式 B（无展开箭头）：[播] -> [放] -> [🚀]
     */
    public boolean detectRocketInStoryBottomZoneFromBitmap(
            Bitmap rawBitmap,
            Rect playNodeBounds,
            boolean restrictToPlayLine
    ) {
        if (rawBitmap == null) return false;

        int rawW = rawBitmap.getWidth();
        int rawH = rawBitmap.getHeight();
        if (rawW < 100 || rawH < 200) return false;

        int normW = 460;
        int normH = Math.round(460f * rawH / rawW);

        int cropTopRaw;
        int cropBotRaw;
        boolean usePlayBounds = playNodeBounds != null
                && playNodeBounds.top > rawH * 0.58f && playNodeBounds.bottom < rawH * 0.96f;
        if (usePlayBounds) {
            int pad = Math.max(20, rawH / 45);
            int maxBandHeight = Math.max(rawH / 10, 110);
            int topLimit = Math.max(playNodeBounds.top - pad, playNodeBounds.bottom - maxBandHeight);
            cropTopRaw = Math.max((int) (rawH * 0.60f), topLimit);
            cropBotRaw = Math.min((int) (rawH * 0.95f), playNodeBounds.bottom + pad);
        } else {
            cropTopRaw = (int) (rawH * 0.68f);
            cropBotRaw = (int) (rawH * 0.94f);
        }

        // 横向扫描扩展到 78% 屏宽（兼容长标题与播放量同行时小火箭位于 x=305..330 的情况）
        int cropWidthRaw = (int) (rawW * 0.78f);
        int cropHeightRaw = cropBotRaw - cropTopRaw;
        if (cropWidthRaw <= 20 || cropHeightRaw <= 14) return false;

        float scale = (float) normW / (float) rawW;
        int targetStripW = Math.max(40, Math.round(cropWidthRaw * scale));
        int targetStripH = Math.max(14, Math.round(cropHeightRaw * scale));

        Bitmap croppedRaw = null;
        Bitmap stripBmp = null;
        try {
            croppedRaw = Bitmap.createBitmap(rawBitmap, 0, cropTopRaw, cropWidthRaw, cropHeightRaw);
            stripBmp = Bitmap.createScaledBitmap(croppedRaw, targetStripW, targetStripH, true);

            int scanLeft = (int) (normW * 0.02f);
            int scanRight = targetStripW - 2;

            int[] px = new int[targetStripW * targetStripH];
            stripBmp.getPixels(px, 0, targetStripW, 0, 0, targetStripW, targetStripH);

            // Step 1: 利用“无彩度白/灰亮像素 + 边缘跳变”切分独立文字行
            List<int[]> textLines = new ArrayList<>();
            boolean inLine = false;
            int lineStart = 0;
            int emptyGap = 0;

            for (int y = 0; y < targetStripH; y++) {
                int edgeHits = 0;
                int prevLum = 0;
                int rowOffset = y * targetStripW;
                for (int x = scanLeft; x <= scanRight; x += 2) {
                    int pixel = px[rowOffset + x];
                    int r = Color.red(pixel);
                    int g = Color.green(pixel);
                    int b = Color.blue(pixel);
                    int lum = (r + g + b) / 3;
                    int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));

                    if (lum > 100 && chroma <= 45 && (Math.abs(lum - prevLum) > 28 || prevLum > 100)) {
                        edgeHits++;
                    }
                    prevLum = lum;
                }

                if (edgeHits >= 3) {
                    if (!inLine) {
                        inLine = true;
                        lineStart = y;
                    }
                    emptyGap = 0;
                } else if (inLine) {
                    emptyGap++;
                    if (emptyGap >= 2) {
                        int lineEnd = y - emptyGap;
                        int h = lineEnd - lineStart + 1;
                        if (h >= 10 && h <= (normH / 22)) {
                            textLines.add(new int[]{lineStart, lineEnd, h});
                        }
                        inLine = false;
                    }
                }
            }
            if (inLine) {
                int h = targetStripH - lineStart;
                if (h >= 10 && h <= (normH / 22)) {
                    textLines.add(new int[]{lineStart, targetStripH - 1, h});
                }
            }

            // Step 2: 从底向上逐行扫描，同时支持模式 A（播+放+⌄+🚀）与模式 B（播+放+🚀）
            for (int lineIdx = textLines.size() - 1; lineIdx >= 0; lineIdx--) {
                int[] line = textLines.get(lineIdx);
                int lnTop = line[0];
                int lnBot = line[1];
                int textH = line[2];
                int splitY = lnTop + (int) (textH * 0.42f);

                // glyph 数组结构: {gStart, gEnd, width, topHits, botHits, verticalSpan, topLeft3x3Hits}
                List<int[]> glyphs = new ArrayList<>();
                boolean inG = false;
                int gStart = 0;
                int topHits = 0;
                int botHits = 0;
                int gMinY = lnBot;
                int gMaxY = lnTop;

                for (int x = scanLeft; x <= scanRight; x++) {
                    int colTop = 0;
                    int colBot = 0;
                    int colMinY = lnBot + 1;
                    int colMaxY = lnTop - 1;

                    for (int y = lnTop; y <= lnBot; y++) {
                        int pixel = px[y * targetStripW + x];
                        int r = Color.red(pixel);
                        int g = Color.green(pixel);
                        int b = Color.blue(pixel);
                        int lum = (r + g + b) / 3;
                        int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));

                        if (lum > 95 && chroma <= 45) {
                            if (y < splitY) colTop++;
                            else colBot++;
                            if (y < colMinY) colMinY = y;
                            if (y > colMaxY) colMaxY = y;
                        }
                    }

                    if (colTop + colBot > 0) {
                        if (!inG) {
                            inG = true;
                            gStart = x;
                            topHits = 0;
                            botHits = 0;
                            gMinY = colMinY;
                            gMaxY = colMaxY;
                        } else {
                            if (colMinY < gMinY) gMinY = colMinY;
                            if (colMaxY > gMaxY) gMaxY = colMaxY;
                        }
                        topHits += colTop;
                        botHits += colBot;
                    } else if (inG) {
                        int gEnd = x - 1;
                        int w = gEnd - gStart + 1;
                        int vSpan = Math.max(1, gMaxY - gMinY + 1);

                        // 小火箭向右上倾斜，其左上角 3x3 区域必然为空黑（<=1 个亮像素）
                        int tlHits = 0;
                        int txMax = Math.min(gEnd, gStart + 2);
                        int tyMax = Math.min(gMaxY, gMinY + 2);
                        for (int tx = gStart; tx <= txMax; tx++) {
                            for (int ty = gMinY; ty <= tyMax; ty++) {
                                int tp = px[ty * targetStripW + tx];
                                int tLum = (Color.red(tp) + Color.green(tp) + Color.blue(tp)) / 3;
                                if (tLum > 95) tlHits++;
                            }
                        }

                        glyphs.add(new int[]{gStart, gEnd, w, topHits, botHits, vSpan, tlHits});
                        inG = false;
                    }
                }

                for (int i = 2; i < glyphs.size(); i++) {
                    int[] gPrev2 = glyphs.get(i - 2);
                    int[] gPrev1 = glyphs.get(i - 1);
                    int[] rock = glyphs.get(i);

                    int gap1 = gPrev1[0] - gPrev2[1] - 1;
                    int gapRock = rock[0] - gPrev1[1] - 1;

                    int rockW = rock[2];
                    int rockTop = rock[3];
                    int rockBot = rock[4];
                    int rockVSpan = rock[5];
                    int rockTL = rock[6];

                    float rockArea = Math.max(1f, rockW * (float) textH);
                    float rockFill = (rockTop + rockBot) / rockArea;

                    // 小火箭轮廓几何特征（兼容有箭头/无箭头两种间距 4~15px）
                    boolean isRocketShape = gapRock >= 4 && gapRock <= 15
                            && rockW >= (textH * 0.55f) && rockW <= (textH * 1.28f)
                            && rockTop >= 12 && rockTop <= 44
                            && rockBot >= 30 && rockBot <= 68
                            && rockBot > rockTop
                            && rockVSpan >= (textH * 0.68f)
                            && rockFill >= 0.20f && rockFill <= 0.52f
                            && rockTL <= 1;

                    if (!isRocketShape) continue;

                    // 模式 A：[放] + [⌄] + [🚀]
                    boolean isPatternA = gap1 >= 0 && gap1 <= 8
                            && gPrev2[3] >= 8 && gPrev2[4] >= 10
                            && gPrev1[2] >= (textH * 0.38f) && gPrev1[2] <= (textH * 1.18f)
                            && gPrev1[3] <= 4 && gPrev1[4] >= 10 && gPrev1[4] <= 44;

                    // 模式 B：[播] + [放] + [🚀]（无展开箭头直连，如“118.2万播放 🚀”）
                    boolean isPatternB = gap1 >= 1 && gap1 <= 6
                            && gPrev2[2] >= (textH * 0.58f) && gPrev2[2] <= (textH * 1.18f)
                            && gPrev2[3] >= 22 && gPrev2[4] >= 32
                            && gPrev1[2] >= (textH * 0.58f) && gPrev1[2] <= (textH * 1.18f)
                            && gPrev1[3] >= 18 && gPrev1[4] >= 32;

                    boolean isEndOfRun = (i == glyphs.size() - 1)
                            || (glyphs.get(i + 1)[0] - rock[1] - 1 >= 12);

                    if ((isPatternA || isPatternB) && isEndOfRun) {
                        return true;
                    }
                }
            }
            return false;
        } finally {
            if (stripBmp != null && stripBmp != croppedRaw) stripBmp.recycle();
            if (croppedRaw != null && croppedRaw != rawBitmap) croppedRaw.recycle();
        }
    }

    public void executeSkipSwipe(
            AccessibilityService service,
            int screenWidth,
            int screenHeight,
            String reason,
            String videoSignature
    ) {
        if (!canSwipeFor(videoSignature)) return;

        lastSwipeTimestamp = SystemClock.uptimeMillis();
        lastSkippedSignature = videoSignature != null ? videoSignature : "";
        markConfirmedPromo(videoSignature);
        FilterConfig.skippedRocketCount++;
        FilterConfig.addLog("🚀 极速划走火箭推广 [" + reason + "]");

        float startX = screenWidth * 0.52f;
        float startY = screenHeight * 0.78f;
        float endX = screenWidth * 0.48f;
        float endY = screenHeight * 0.14f;

        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, 65L);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        boolean dispatched = service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                releaseCooldownForRetry();
                FilterConfig.addLog("⚠️ 上滑手势被系统取消，立即重试");
            }
        }, null);
        if (!dispatched) {
            releaseCooldownForRetry();
        }
    }

    private void releaseCooldownForRetry() {
        lastSwipeTimestamp = SystemClock.uptimeMillis() - SWIPE_COOLDOWN_MS + 90L;
        lastSkippedSignature = "";
    }
}
