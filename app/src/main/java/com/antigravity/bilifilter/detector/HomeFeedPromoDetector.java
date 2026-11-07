package com.antigravity.bilifilter.detector;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.antigravity.bilifilter.config.FilterConfig;
import com.antigravity.bilifilter.overlay.OverlayMaskManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 主页（首页双列推荐流）带火箭/推广视频卡片检测器（v1.5 滑动跟随锚点 + 边缘裁剪版）
 *
 * 1. 绝不匹配空文本占位符（如 rcmd_tag / ad_tint），普通 [UP] 视频零误判；
 * 2. 为命中的推广卡片绑定其容器 AccessibilityNodeInfo（anchorNode）与所属 RecyclerView 视口（clipBounds），
 *    让遮罩在手指上下滑动主页时实时读取卡片真实坐标同步移动，并在顶栏/底栏边缘自动平滑裁剪；
 * 3. 兼容刚滑入屏幕底部或即将滑出屏幕顶部的半截卡片，杜绝滑到一半遮罩才出现或提前消失。
 */
public class HomeFeedPromoDetector {

    private static final List<String> EXPLICIT_PROMO_LABELS = Arrays.asList(
            "创作推广",
            "商业推广",
            "花火推广",
            "起飞推广",
            "品牌推广",
            "直播推广",
            "游戏推广",
            "活动推广",
            "推广",
            "广告",
            "赞助",
            "立即下载",
            "去看看",
            "查看详情",
            "立即购买"
    );

    private static final List<String> STRICT_PROMO_VIEW_ID_HINTS = Arrays.asList(
            "creativity_promo",
            "rocket_tag",
            "commercial_tag",
            "promo_tag"
    );

    private static class CardAnchorResult {
        final AccessibilityNodeInfo cardNode;
        final Rect cardBounds;
        final Rect listClipBounds;

        CardAnchorResult(AccessibilityNodeInfo cardNode, Rect cardBounds, Rect listClipBounds) {
            this.cardNode = cardNode;
            this.cardBounds = cardBounds;
            this.listClipBounds = listClipBounds;
        }
    }

    public List<OverlayMaskManager.MaskItemSpec> detectPromotedHomeCards(
            AccessibilityNodeInfo rootNode,
            int screenWidth,
            int screenHeight
    ) {
        List<OverlayMaskManager.MaskItemSpec> cardMasks = new ArrayList<>();
        if (!FilterConfig.isEnableHomePromoMask() || rootNode == null) {
            return cardMasks;
        }

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(rootNode);
        Rect rect = new Rect();
        Set<String> keywords = FilterConfig.getPromoKeywords();

        int defaultClipTop = (int) (screenHeight * 0.145f);
        int defaultClipBottom = (int) (screenHeight * 0.915f);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();

            if (node.isVisibleToUser()) {
                node.getBoundsInScreen(rect);
                String viewId = node.getViewIdResourceName() != null
                        ? node.getViewIdResourceName().toLowerCase() : "";
                String text = node.getText() != null
                        ? node.getText().toString().trim() : "";
                String desc = node.getContentDescription() != null
                        ? node.getContentDescription().toString().trim() : "";

                boolean isBadgeSized = rect.height() >= 8 && rect.height() <= (screenHeight / 16)
                        && rect.width() >= 10 && rect.width() <= (screenWidth * 0.38f)
                        && rect.bottom > defaultClipTop && rect.top < defaultClipBottom;

                if (isBadgeSized) {
                    String matchedReason = null;
                    String label = !text.isEmpty() ? text : desc;

                    if (!label.isEmpty() && label.length() <= 6) {
                        for (String explicit : EXPLICIT_PROMO_LABELS) {
                            if (explicit.equals(label)) {
                                matchedReason = label;
                                break;
                            }
                        }
                        if (matchedReason == null) {
                            for (String kw : keywords) {
                                if (!"推广".equals(kw) && !"广告".equals(kw) && label.equals(kw)) {
                                    matchedReason = label;
                                    break;
                                }
                            }
                        }
                    }

                    if (matchedReason == null && !viewId.isEmpty()) {
                        for (String hint : STRICT_PROMO_VIEW_ID_HINTS) {
                            if (viewId.contains(hint)) {
                                matchedReason = "火箭推广";
                                break;
                            }
                        }
                    }

                    if (matchedReason != null) {
                        CardAnchorResult anchor = findEnclosingHomeCard(
                                node, screenWidth, screenHeight, defaultClipTop, defaultClipBottom
                        );
                        if (anchor != null && !containsSimilarRect(cardMasks, anchor.cardBounds)) {
                            cardMasks.add(new OverlayMaskManager.MaskItemSpec(
                                    anchor.cardBounds,
                                    matchedReason,
                                    -1,
                                    anchor.cardNode,
                                    anchor.listClipBounds
                            ));
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

        return cardMasks;
    }

    private CardAnchorResult findEnclosingHomeCard(
            AccessibilityNodeInfo tagNode,
            int screenWidth,
            int screenHeight,
            int defaultClipTop,
            int defaultClipBottom
    ) {
        AccessibilityNodeInfo current = tagNode;
        AccessibilityNodeInfo matchedCardNode = null;
        Rect matchedCardRect = null;
        Rect rect = new Rect();

        int depth = 0;
        while (current != null && depth < 9) {
            current.getBoundsInScreen(rect);
            int w = rect.width();
            int h = rect.height();

            // 双列卡片宽度恒定为屏幕宽度的 39%~54%
            boolean isTwoColumnWidth = w >= (screenWidth * 0.39f) && w <= (screenWidth * 0.54f);
            if (isTwoColumnWidth && matchedCardNode == null) {
                boolean nearVerticalEdge = rect.top <= defaultClipTop + 28 || rect.bottom >= defaultClipBottom - 28;
                int minHeight = nearVerticalEdge ? (int) (screenHeight * 0.045f) : (int) (screenHeight * 0.13f);
                if (h >= minHeight && h <= (screenHeight * 0.38f)) {
                    matchedCardNode = current;
                    matchedCardRect = new Rect(rect);
                }
            }

            // 继续向上寻找包裹卡片的 RecyclerView 视口边界
            String cls = current.getClassName() != null ? current.getClassName().toString() : "";
            if (matchedCardNode != null
                    && (cls.contains("RecyclerView") || current.isScrollable())
                    && w >= (screenWidth * 0.85f)) {
                Rect clip = new Rect(
                        0,
                        Math.max(defaultClipTop, rect.top),
                        screenWidth,
                        Math.min(defaultClipBottom, rect.bottom)
                );
                return new CardAnchorResult(matchedCardNode, matchedCardRect, clip);
            }

            current = current.getParent();
            depth++;
        }

        if (matchedCardNode != null) {
            Rect fallbackClip = new Rect(0, defaultClipTop, screenWidth, defaultClipBottom);
            return new CardAnchorResult(matchedCardNode, matchedCardRect, fallbackClip);
        }
        return null;
    }

    private boolean containsSimilarRect(List<OverlayMaskManager.MaskItemSpec> list, Rect target) {
        for (OverlayMaskManager.MaskItemSpec item : list) {
            if (Math.abs(item.bounds.centerX() - target.centerX()) < 45
                    && Math.abs(item.bounds.centerY() - target.centerY()) < 45) {
                return true;
            }
        }
        return false;
    }
}
