package tn.eluea.kgpt.ui.lab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fixed output length options + custom option.
 *
 * Note: Fixed option titles/subtitles are intentionally kept exactly as provided.
 */
public final class OutputLengthOptions {

    private OutputLengthOptions() {}

    // Exactly 15 fixed levels in the specified (title | subtitle) format.
    private static final String[][] FIXED = new String[][]{
            {"64 Token【50字.48词】", "起标题 / 关键词提取 / 简短分类"},
            {"128 Token【100字.96词】", "一句话问答 / 短句翻译"},
            {"256 Token【200字.190词】", "简短摘要 / 小段落说明"},
            {"512 Token【400字.380词】", "常规简短沟通"},
            {"1024 Token【800字.760词】", "邮件撰写 / 详细解答"},
            {"2048 Token【1.6k字.1.5k词】", "逻辑分析 / 短篇脚本生成"},
            {"4096 Token【3.2k字.3k词】", "常规模型极限 / 完整模块代码"},
            {"8192 Token【6.5k字.6.1k词】", "深度长文 / 复杂代码重构"},
            {"16384 Token【1.3万字.1.2万词】", "长篇报告 / 扩展级代码生成"},
            {"32768 Token【2.6万字.2.4万词】", "o1-preview 级输出极限"},
            {"49152 Token【3.9万字.3.6万词】", "复杂项目的多步推理与输出"},
            {"65536 Token【5.2万字.4.9万词】", "o1-mini 级输出极限 / 论文级"},
            {"81920 Token【6.5万字.6.1万词】", "超长文本深度处理"},
            {"100000 Token【8万字.7.5万词】", "o1-Pro 级输出极限"},
            {"131072 Token【10万字.9.8万词】", "前沿模型大窗口极限"},
    };

    public static List<OutputLengthOption> buildOptions(String customTitle, String customSubtitle) {
        ArrayList<OutputLengthOption> list = new ArrayList<>();
        for (String[] row : FIXED) {
            int tokens = parseLeadingInt(row[0]);
            list.add(new OutputLengthOption(tokens, row[0], row[1], false));
        }
        list.add(new OutputLengthOption(-1, customTitle, customSubtitle, true));
        return Collections.unmodifiableList(list);
    }

    public static OutputLengthOption findFixedByTokens(int tokens) {
        for (String[] row : FIXED) {
            int t = parseLeadingInt(row[0]);
            if (t == tokens) {
                return new OutputLengthOption(t, row[0], row[1], false);
            }
        }
        return null;
    }

    /** Extracts the integer from the beginning of the title, e.g. "64 Token..." -> 64 */
    public static int parseLeadingInt(String s) {
        if (s == null) return 0;
        int n = s.length();
        int i = 0;
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        int start = i;
        while (i < n) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            i++;
        }
        if (i <= start) return 0;
        try {
            return Integer.parseInt(s.substring(start, i));
        } catch (Throwable t) {
            return 0;
        }
    }
}
