package tn.eluea.kgpt.features.textactions;

import tn.eluea.kgpt.features.textactions.domain.TextAction;

/**
 * Provides system prompts for each text action.
 * Follows Clean Code principles by separating data from logic.
 */
public class TextActionPrompts {

    private static boolean isZhLocale() {
        try {
            String lang = java.util.Locale.getDefault() != null ? java.util.Locale.getDefault().getLanguage() : "";
            return lang != null && lang.startsWith("zh");
        } catch (Throwable ignored) {}
        return false;
    }

    private static final String PROMPT_REPHRASE = "You are a text rephrasing assistant. Rephrase the given text while keeping the exact same meaning. "
            +
            "Maintain the same language as the input. Only output the rephrased text, nothing else.";

    private static final String PROMPT_FIX_ERRORS = "You are a grammar and spelling correction assistant. Fix all grammar, spelling, and punctuation errors in the given text. "
            +
            "Maintain the same language and meaning. Only output the corrected text, nothing else.";

    private static final String PROMPT_IMPROVE = "You are a writing improvement assistant. Improve the style, clarity, and flow of the given text while keeping the same meaning. "
            +
            "Maintain the same language. Only output the improved text, nothing else.";

    private static final String PROMPT_EXPAND = "You are a text expansion assistant. Expand the given text by adding more details, examples, or explanations while keeping the core meaning. "
            +
            "Maintain the same language. Only output the expanded text, nothing else.";

    private static final String PROMPT_SHORTEN = "You are a text summarization assistant. Shorten the given text while keeping the essential meaning and key points. "
            +
            "Maintain the same language. Only output the shortened text, nothing else.";

    private static final String PROMPT_FORMAL = "You are a tone adjustment assistant. Convert the given text to a formal, professional tone. "
            +
            "Maintain the same language and meaning. Only output the formal version, nothing else.";

    private static final String PROMPT_CASUAL = "You are a tone adjustment assistant. Convert the given text to a casual, friendly tone. "
            +
            "Maintain the same language and meaning. Only output the casual version, nothing else.";

    private static final String PROMPT_TRANSLATE_AUTO = "You are a translation assistant. Detect the language of the input text and translate it to the opposite language "
            +
            "(if Arabic, translate to English; if English, translate to Arabic; for other languages, translate to English). "
            +
            "Only output the translated text, nothing else.";


    // 简体中文默认提示词（用于二级菜单“编辑提示词/重置”及实际调用）
    private static final String PROMPT_REPHRASE_ZH = "你是一个文本改写助手。请在保持原意不变的前提下改写下面的文本。保持与输入相同的语言。只输出改写后的文本，不要输出任何其他内容。";
    private static final String PROMPT_FIX_ERRORS_ZH = "你是一个语法与拼写纠错助手。请修正下面文本中的语法、拼写和标点错误，保持原语言与原意不变。只输出修正后的文本，不要输出任何其他内容。";
    private static final String PROMPT_IMPROVE_ZH = "你是一个写作优化助手。请在保持原意不变的前提下提升下面文本的表达、清晰度和流畅度。保持与输入相同的语言。只输出优化后的文本，不要输出任何其他内容。";
    private static final String PROMPT_EXPAND_ZH = "你是一个扩写助手。请在保持核心意思不变的前提下，为下面文本补充细节、例子或解释，使内容更完整。保持与输入相同的语言。只输出扩写后的文本，不要输出任何其他内容。";
    private static final String PROMPT_SHORTEN_ZH = "你是一个精简助手。请在保留关键信息与核心意思的前提下精简下面文本。保持与输入相同的语言。只输出精简后的文本，不要输出任何其他内容。";
    private static final String PROMPT_FORMAL_ZH = "你是一个语气转换助手。请将下面文本改写为正式、专业的语气，保持原语言与原意不变。只输出转换后的文本，不要输出任何其他内容。";
    private static final String PROMPT_CASUAL_ZH = "你是一个语气转换助手。请将下面文本改写为口语、友好的语气，保持原语言与原意不变。只输出转换后的文本，不要输出任何其他内容。";
    private static final String PROMPT_TRANSLATE_AUTO_ZH = "你是翻译助手。请自动检测输入语言并翻译为目标语言（若输入为中文则翻译为英文，若输入为英文则翻译为中文，其他语言默认翻译为英文）。只输出翻译结果，不要输出任何其他内容。";
    private static final String PROMPT_DEFAULT_ZH = "请处理以下文本：";

    private static final String PROMPT_DEFAULT = "Process the following text:";

    /**
     * Get the system message for a specific action.
     */
    public static String getSystemMessage(TextAction action) {
        return getSystemMessage(action, null);
    }

    /**
     * Get the system message for a specific action, with optional target info.
     */
    public static String getSystemMessage(TextAction action, String targetInfo) {
        switch (action) {
            case REPHRASE:
                return isZhLocale() ? PROMPT_REPHRASE_ZH : PROMPT_REPHRASE;
            case FIX_ERRORS:
                return isZhLocale() ? PROMPT_FIX_ERRORS_ZH : PROMPT_FIX_ERRORS;
            case IMPROVE:
                return isZhLocale() ? PROMPT_IMPROVE_ZH : PROMPT_IMPROVE;
            case EXPAND:
                return isZhLocale() ? PROMPT_EXPAND_ZH : PROMPT_EXPAND;
            case SHORTEN:
                return isZhLocale() ? PROMPT_SHORTEN_ZH : PROMPT_SHORTEN;
            case FORMAL:
                return isZhLocale() ? PROMPT_FORMAL_ZH : PROMPT_FORMAL;
            case CASUAL:
                return isZhLocale() ? PROMPT_CASUAL_ZH : PROMPT_CASUAL;
            case TRANSLATE:
                if (targetInfo != null && !targetInfo.isEmpty()) {
                    if (isZhLocale()) {
                        return "你是翻译助手。请将下面文本翻译为 " + targetInfo + "。只输出翻译结果，不要输出任何其他内容。";
                    }
                    return "You are a translation assistant. Translate the given text to " + targetInfo + ". " +
                            "Only output the translated text, nothing else.";
                }
                return isZhLocale() ? PROMPT_TRANSLATE_AUTO_ZH : PROMPT_TRANSLATE_AUTO;
            default:
                return isZhLocale() ? PROMPT_DEFAULT_ZH : PROMPT_DEFAULT;
        }
    }

    /**
     * Build the full prompt for the AI.
     */
    public static String buildPrompt(TextAction action, String selectedText) {
        return selectedText;
    }
}
