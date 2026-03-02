package tn.eluea.kgpt.llm.client;

import tn.eluea.kgpt.llm.LanguageModel;

public class DoubaoClient extends ChatGPTClient {
    @Override
    public LanguageModel getLanguageModel() {
        return LanguageModel.Doubao;
    }
}
