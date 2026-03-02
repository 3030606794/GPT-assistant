package tn.eluea.kgpt.llm.client;

import tn.eluea.kgpt.llm.LanguageModel;

/**
 * xAI (Grok) OpenAI-compatible client.
 */
public class GrokClient extends ChatGPTClient {
    @Override
    public LanguageModel getLanguageModel() {
        return LanguageModel.Grok;
    }
}
