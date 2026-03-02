package tn.eluea.kgpt.ui.lab;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed options for "普通模型思考" (temperature-like).
 * Values are 0.0 .. 2.0.
 */
public final class NormalModelThinkingOptions {

    private NormalModelThinkingOptions() {}

    // IMPORTANT: Keep this data EXACTLY as configured.
    private static final Object[][] DATA = new Object[][]{
            {0.0f, "0.0【绝对精准】", "剥夺一切创造力，每次提问都只会输出唯一且绝对相同的答案。适合：写代码、查 Bug、数据提取、严格的 JSON 格式化。"},
            {0.1f, "0.1【极度保守】", "几乎无变体，语气极其生硬死板。适合：数学逻辑推演、严格的格式转换。"},
            {0.2f, "0.2【严谨客观】", "允许极微小的同义词替换，整体依旧冰冷。适合：技术开发文档撰写、冰冷的官方公文。"},
            {0.3f, "0.3【稳定克制】", "以事实为中心，不带任何感情色彩和个人主观评价。适合：长文去水总结、精准的外语翻译。"},
            {0.4f, "0.4【稳重专业】", "像专业的客服或秘书，用词准确，表达清晰，不会瞎发挥。适合：商务邮件撰写、标准操作指南（SOP）。"},
            {0.5f, "0.5【中规中矩】", "保守与灵活的平衡点，语气开始柔和，不再像机器。适合：知识库问答、科普解答。"},
            {0.6f, "0.6【自然得体】", "交流不突兀，用词比较舒服。适合：日常助理问答、结构化文章撰写。"},
            {0.7f, "0.7【灵活生动】", "最推荐的日常聊天默认值。既不会死板，也不会乱跑题。适合：通用对话、生活建议、情感倾诉。"},
            {0.8f, "0.8【表达丰富】", "主动使用更生动的修辞、成语和多变的句式。适合：博客文章、润色修改现有文案。"},
            {0.9f, "0.9【思维活跃】", "略微放飞自我，偶尔会给你一些意想不到的切入点。适合：营销文案、社交媒体文案。"},
            {1.0f, "1.0【充满创意】", "标准的高创造力基准线。适合：头脑风暴、创意点子收集、故事大纲构思。"},
            {1.1f, "1.1【天马行空】", "敢于打破常规逻辑，使用罕见词汇。适合：小说创作、角色扮演。"},
            {1.2f, "1.2【极度发散】", "注重意象和跳跃，偶尔会显得有些神经过敏。适合：写现代诗、构思抽象的艺术概念。"},
            {1.3f, "1.3【边缘试探】", "非常容易跑题，开始出现常人难以理解的跳跃性思维。适合：荒诞派文学尝试、无厘头笑话。"},
            {1.4f, "1.4【逻辑破碎】", "前言不搭后语的概率极高，为了不走寻常路而强行拼凑词汇。适合：模拟醉汉发疯、极端压力测试。"},
            {1.5f, "1.5【严重幻觉】", "开始一本正经地胡编乱造不存在的知识、事实和词语。"},
            {1.6f, "1.6【语无伦次】", "句法结构开始崩塌，读起来非常费劲，就像一个人在梦游时说话。"},
            {1.7f, "1.7【意识流】", "词语之间毫无关联，像是在随机翻字典念词。"},
            {1.8f, "1.8【纯粹乱码】", "可能出现中英文夹杂的无意义字符组合、甚至乱码符号。"},
    };

    public static List<NormalModelThinkingOption> buildOptions() {
        ArrayList<NormalModelThinkingOption> list = new ArrayList<>();
        for (Object[] row : DATA) {
            list.add(new NormalModelThinkingOption((Float) row[0], (String) row[1], (String) row[2]));
        }
        return list;
    }

    public static NormalModelThinkingOption findByValue(float value) {
        int key = Math.round(value * 10.0f);
        for (Object[] row : DATA) {
            float v = (Float) row[0];
            if (Math.round(v * 10.0f) == key) {
                return new NormalModelThinkingOption(v, (String) row[1], (String) row[2]);
            }
        }
        return null;
    }

    public static int indexOf(float value) {
        int key = Math.round(value * 10.0f);
        for (int i = 0; i < DATA.length; i++) {
            float v = (Float) DATA[i][0];
            if (Math.round(v * 10.0f) == key) return i;
        }
        return -1;
    }
}
