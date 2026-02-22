package tn.eluea.kgpt.ui.lab;

import java.util.ArrayList;
import java.util.List;

/**
 * UI + logic options for "推理模型思考".
 */
public final class ReasoningModelThinkingOptions {

    private ReasoningModelThinkingOptions() {}

    public static final int DIVERGENT = 0;
    public static final int CONVERGENT = 1;
    public static final int AUTO = 2;
    public static final int LOW = 3;
    public static final int MEDIUM = 4;
    public static final int HIGH = 5;

    // Third line hints (based on the user's screenshot model list)
    private static final String HINT_COMMON = "";
    private static final String HINT_DEPTH = "";

    private static final Object[][] DATA = new Object[][]{
            {DIVERGENT, "发散性思考", "脑洞大开，思维跳跃，适合写诗、写小说、头脑风暴", HINT_COMMON},
            {CONVERGENT, "收敛性思考", "极其保守死板，绝不废话，适合写代码、处理格式化数据", HINT_COMMON},
            {AUTO, "自动思考 (默认)", "由模型自动决定最佳的逻辑推演深度", HINT_COMMON},
            {LOW, "低档深度 (Low)", "响应最快，后台推演较少，适合日常简单逻辑问答", HINT_DEPTH},
            {MEDIUM, "中档深度 (Medium)", "兼顾质量与速度，适合常规编程与复杂任务", HINT_DEPTH},
            {HIGH, "高档深度 (High)", "极致推演，耗时极长但逻辑最严密", HINT_DEPTH},
    };

    public static List<ReasoningModelThinkingOption> buildOptions() {
        ArrayList<ReasoningModelThinkingOption> list = new ArrayList<>();
        for (Object[] row : DATA) {
            list.add(new ReasoningModelThinkingOption((Integer) row[0], (String) row[1], (String) row[2], (String) row[3]));
        }
        return list;
    }

    public static int indexOf(int id) {
        for (int i = 0; i < DATA.length; i++) {
            if (((Integer) DATA[i][0]) == id) return i;
        }
        return -1;
    }

    public static ReasoningModelThinkingOption findById(int id) {
        for (Object[] row : DATA) {
            if (((Integer) row[0]) == id) {
                return new ReasoningModelThinkingOption((Integer) row[0], (String) row[1], (String) row[2], (String) row[3]);
            }
        }
        return null;
    }

    /**
     * Append a compact instruction into system message.
     * This is only used when the active model is considered a reasoning model.
     */
    public static String applyToSystemMessage(String systemMessage, int id) {
        String instruction = buildInstruction(id);
        if (instruction == null || instruction.trim().isEmpty()) {
            return systemMessage;
        }
        String base = systemMessage == null ? "" : systemMessage;
        if (base.trim().isEmpty()) {
            return "[推理模型思考]\n" + instruction;
        }
        return base + "\n\n[推理模型思考]\n" + instruction;
    }

    private static String buildInstruction(int id) {
        switch (id) {
            case DIVERGENT:
                return "请采用发散性思考：给出多个新颖角度与可能性，但不要胡编事实。";
            case CONVERGENT:
                return "请采用收敛性思考：只给出最稳妥、最直接的结论，避免跑题与废话。";
            case AUTO:
                return "";
            case LOW:
                return "推理深度：低（Low）。请尽量快速给出答案，减少后台推演，不要输出推理过程。";
            case MEDIUM:
                return "推理深度：中（Medium）。在质量与速度间平衡，不要输出推理过程。";
            case HIGH:
                return "推理深度：高（High）。请在内部进行尽可能充分的推演后再给出答案，不要输出推理过程。";
            default:
                return "";
        }
    }
}
