package tn.eluea.kgpt.llm.client;

import tn.eluea.kgpt.llm.LanguageModel;

/**
 * DashScope (Qwen) OpenAI-compatible client.
 * Uses the same request/streaming logic as ChatGPTClient.
 */
public class QwenClient extends ChatGPTClient {
    @Override
    public LanguageModel getLanguageModel() {
        return LanguageModel.Qwen;
    }
}
