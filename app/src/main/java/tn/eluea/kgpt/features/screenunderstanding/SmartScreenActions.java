package tn.eluea.kgpt.features.screenunderstanding;

import java.util.ArrayList;
import java.util.List;

import tn.eluea.kgpt.features.textactions.domain.CustomTextAction;

/**
 * "Light" screen understanding actions.
 *
 * These actions assume we already have page text via:
 * - Accessibility (window content text)
 * - Share sheet
 * - Copy/paste
 *
 * No OCR.
 */
public final class SmartScreenActions {

    public static final String ID_NEWS = "__smart_screen_news__";
    public static final String ID_SHOPPING = "__smart_screen_shop__";
    public static final String ID_EXTRACT = "__smart_screen_extract__";

    private SmartScreenActions() {
    }

    public static boolean isSmartActionId(String id) {
        return ID_NEWS.equals(id) || ID_SHOPPING.equals(id) || ID_EXTRACT.equals(id);
    }

    public static List<CustomTextAction> buildActionsFor(String text, String url, String contextHint) {
        ArrayList<CustomTextAction> list = new ArrayList<>();

        // Only show smart actions when it looks like a page (or when explicitly launched from share/accessibility).
        boolean forced = contextHint != null && ("share".equals(contextHint) || "accessibility".equals(contextHint));
        boolean pageLike = forced || ScreenTextHeuristics.isLikelyPageText(text) || (url != null && !url.trim().isEmpty());
        if (!pageLike) return list;

        // Order: most likely first.
        ScreenContentType type = ScreenTextHeuristics.guessType(text, url);
        if (type == ScreenContentType.SHOPPING) {
            list.add(makeShoppingAction());
            list.add(makeNewsAction());
        } else if (type == ScreenContentType.NEWS) {
            list.add(makeNewsAction());
            list.add(makeShoppingAction());
        } else {
            list.add(makeExtractAction());
            list.add(makeNewsAction());
            list.add(makeShoppingAction());
        }
        // Always include the generic extraction action last (if not already).
        if (!containsId(list, ID_EXTRACT)) list.add(makeExtractAction());

        return list;
    }

    public static String pickDefaultActionId(String text, String url) {
        ScreenContentType type = ScreenTextHeuristics.guessType(text, url);
        if (type == ScreenContentType.SHOPPING) return ID_SHOPPING;
        if (type == ScreenContentType.NEWS) return ID_NEWS;
        // For generic page-like text, extraction is safest.
        return ID_EXTRACT;
    }

    public static String buildUserPrompt(String actionId, String pageText, String title, String url) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.trim().isEmpty()) {
            sb.append("Title: ").append(title.trim()).append("\n");
        }
        if (url != null && !url.trim().isEmpty()) {
            sb.append("URL: ").append(url.trim()).append("\n");
        }
        sb.append("\n");

        if (ID_NEWS.equals(actionId)) {
            sb.append("Article/Page text:\n");
        } else if (ID_SHOPPING.equals(actionId)) {
            sb.append("Shopping page text (may include multiple products/variants/specs):\n");
        } else {
            sb.append("Page text:\n");
        }
        sb.append(pageText != null ? pageText : "");
        return sb.toString();
    }

    private static boolean containsId(List<CustomTextAction> list, String id) {
        if (list == null) return false;
        for (CustomTextAction a : list) {
            if (a != null && id.equals(a.id)) return true;
        }
        return false;
    }

    private static CustomTextAction makeNewsAction() {
        return new CustomTextAction(
                ID_NEWS,
                "屏幕理解·新闻要点",
                // System prompt
                "You are a news-page understanding assistant.\n" +
                        "Only use the provided text (do NOT invent facts). If something is missing, say it's unknown.\n" +
                        "Respond in the same language as the provided page text.\n" +
                        "Output format (use markdown):\n" +
                        "1) One-sentence TL;DR\n" +
                        "2) Key points (5-10 bullets)\n" +
                        "3) Who/What/When/Where/Why (structured)\n" +
                        "4) Numbers & quotes (only if present in text)\n" +
                        "5) What to watch next (2-4 bullets)",
                true);
    }

    private static CustomTextAction makeShoppingAction() {
        return new CustomTextAction(
                ID_SHOPPING,
                "屏幕理解·购物参数对比",
                // System prompt
                "You are a shopping-page understanding assistant.\n" +
                        "Only use the provided text (do NOT invent specs/prices). If something is missing, mark it as unknown.\n" +
                        "Respond in the same language as the provided page text.\n" +
                        "Tasks:\n" +
                        "- Extract products/variants, prices, and key specs from the text.\n" +
                        "- If multiple items/variants exist: build a comparison table (key specs as rows).\n" +
                        "- If only one item: output a structured spec sheet.\n" +
                        "- Summarize pros/cons and give buying advice for different user needs/budgets.\n" +
                        "Output format (use markdown):\n" +
                        "1) Summary\n" +
                        "2) Comparison table (or spec sheet)\n" +
                        "3) Pros/Cons\n" +
                        "4) Recommendation",
                true);
    }

    private static CustomTextAction makeExtractAction() {
        return new CustomTextAction(
                ID_EXTRACT,
                "屏幕理解·写作素材",
                // System prompt
                "You are a writing assistant that extracts usable information from a page.\n" +
                        "Only use the provided text (do NOT invent). If something is missing, say it's unknown.\n" +
                        "Respond in the same language as the provided page text.\n" +
                        "Output format (use markdown):\n" +
                        "- Key facts (bullets)\n" +
                        "- Key entities (people/orgs/places)\n" +
                        "- Strong quotes or sentences (only if present)\n" +
                        "- A short outline for writing (3-6 sections)",
                true);
    }
}
