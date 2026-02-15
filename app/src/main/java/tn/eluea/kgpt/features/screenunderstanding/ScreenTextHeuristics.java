package tn.eluea.kgpt.features.screenunderstanding;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristics for classifying shared/captured text as shopping/news.
 *
 * Goals:
 * - Fast and lightweight.
 * - Works offline.
 * - Avoid any heavy OCR or ML dependencies.
 */
public final class ScreenTextHeuristics {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private ScreenTextHeuristics() {
    }

    public static String findFirstUrl(String text) {
        if (text == null) return null;
        try {
            Matcher m = URL_PATTERN.matcher(text);
            if (m.find()) {
                return m.group();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean looksLikeUrlOnly(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        // If it's mostly a URL (or title + URL), treat as URL-only.
        String url = findFirstUrl(t);
        if (url == null) return false;
        // Remove URL and see if remaining content is meaningful.
        String rest = t.replace(url, "").trim();
        // Often share content is: "Title\nhttps://...".
        // If rest is short, we consider it url-only.
        return rest.length() < 80 && t.length() < 260;
    }

    public static boolean isLikelyPageText(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.length() < 300) return false;
        int lines = 1;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n') lines++;
        }
        // Lots of lines or enough paragraphs
        return lines >= 6 || t.length() >= 800;
    }

    public static ScreenContentType guessType(String text, String url) {
        if (text == null) return ScreenContentType.GENERIC;
        String t = text.trim();
        if (t.isEmpty()) return ScreenContentType.GENERIC;

        String lower = t.toLowerCase(Locale.ROOT);
        String u = url != null ? url.toLowerCase(Locale.ROOT) : "";

        // URL hints
        if (!u.isEmpty()) {
            if (containsAny(u, "jd.com", "taobao.com", "tmall.com", "amazon.", "bestbuy.", "pinduoduo", "1688.com", "suning", "aliexpress")) {
                return ScreenContentType.SHOPPING;
            }
            if (containsAny(u, "news", "reuters.", "bbc.", "cnn.", "nytimes.", "theguardian.", "wsj.", "bloomberg.", "apnews.", "xinhuanet", "people.com", "cctv")) {
                return ScreenContentType.NEWS;
            }
        }

        // Shopping text cues
        int shopScore = 0;
        if (containsAny(t, "¥", "￥", "$", "价格", "到手价", "优惠", "领券", "库存", "发货", "包邮", "规格", "参数", "型号", "尺寸", "重量", "颜色", "版本", "配置", "品牌", "售后", "保修")) {
            shopScore += 2;
        }
        // Spec-like patterns: many key:value lines.
        int kvLines = countKeyValueLines(t);
        if (kvLines >= 6) shopScore += 2;
        if (kvLines >= 12) shopScore += 2;

        // News text cues
        int newsScore = 0;
        if (containsAny(t, "原标题", "记者", "编辑", "报道", "快讯", "评论", "来源", "新华社", "中新网", "路透", "美联社", "法新社")) {
            newsScore += 2;
        }
        if (containsAny(lower, "breaking", "reporting", "updated", "published")) {
            newsScore += 1;
        }
        if (looksLikeNewsByDate(t)) {
            newsScore += 1;
        }
        // Paragraphs tend to indicate article.
        if (t.length() >= 800) newsScore += 1;

        if (shopScore >= 3 && shopScore >= newsScore + 1) return ScreenContentType.SHOPPING;
        if (newsScore >= 3 && newsScore >= shopScore) return ScreenContentType.NEWS;
        // Weak signals
        if (shopScore >= 2 && kvLines >= 8) return ScreenContentType.SHOPPING;
        if (newsScore >= 2 && t.length() >= 600) return ScreenContentType.NEWS;
        return ScreenContentType.GENERIC;
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null) return false;
        for (String n : needles) {
            if (n == null || n.isEmpty()) continue;
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private static int countKeyValueLines(String t) {
        if (t == null) return 0;
        int count = 0;
        String[] lines = t.split("\\r?\\n");
        for (String line : lines) {
            if (line == null) continue;
            String s = line.trim();
            if (s.length() < 4) continue;
            // Typical spec separators
            int idx = s.indexOf(':');
            if (idx < 0) idx = s.indexOf('：');
            if (idx < 0) idx = s.indexOf('\t');
            if (idx > 0 && idx < Math.min(30, s.length() - 1)) {
                // Key should not be too long
                String key = s.substring(0, idx).trim();
                String val = s.substring(idx + 1).trim();
                if (key.length() >= 1 && key.length() <= 20 && val.length() >= 1) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean looksLikeNewsByDate(String t) {
        if (t == null) return false;
        // Very rough date indicators
        // e.g. 2026-02-15, 2026/02/15, 2026年2月15日
        try {
            return Pattern.compile("(20\\d{2}[-/年]\\d{1,2}([-/月]\\d{1,2})?([日])?)").matcher(t).find();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
