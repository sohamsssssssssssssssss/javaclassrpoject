package com.jade.services.answer;
import com.jade.api.AnswerService;
import com.jade.api.WebSearchProvider;
import com.jade.api.KnowledgeAnswerProvider;
/** Keys stay in provider instances, never in result/history/config diagnostics. */
public final class AnswerConfiguration {
    private AnswerConfiguration() { }
    static final String DEFAULT_LOCAL_MODEL = "llama3.2:latest";
    static final String DEFAULT_MODEL = "gpt-5.4-mini";
    public static AnswerService load() {
        if (setting("answer.provider").equalsIgnoreCase("openai"))
            return configured(System.getenv("OPENAI_API_KEY"), "openai", setting("answer.model"), setting("search.provider"), setting("search.api.key"));
        return local(setting("answer.provider"), setting("local.model"), setting("local.model.endpoint"), setting("search.provider"), setting("search.api.key"));
    }
    static AnswerService local(String provider, String model, String endpoint, String searchProvider, String searchKey) {
        try {
            KnowledgeAnswerProvider knowledge = (provider.isBlank() || provider.equalsIgnoreCase("local"))
                    ? new LocalModelProvider(new OllamaLanguageModel(java.net.URI.create(endpoint.isBlank() ? "http://127.0.0.1:11434" : endpoint), model.isBlank() ? DEFAULT_LOCAL_MODEL : model)) : null;
            WebSearchProvider search = searchProvider.equalsIgnoreCase("brave") && !searchKey.isBlank() ? new BraveWebSearchProvider(searchKey)
                    : searchProvider.isBlank() || searchProvider.equalsIgnoreCase("direct") ? new PublicWebRetrieval() : null;
            return new ResearchAnswerService(knowledge, search);
        } catch (IllegalArgumentException invalidConfiguration) { return new ResearchAnswerService(null, null); }
    }
    static AnswerService configured(String key, String provider, String model, String searchProvider, String searchKey) {
        try {
            OpenAiKnowledgeProvider openai = provider.equalsIgnoreCase("openai") && key != null && !key.isBlank()
                    ? new OpenAiKnowledgeProvider(key, model.isBlank() ? DEFAULT_MODEL : model) : null;
            // Legacy cloud opt-in answers knowledge only. Retrieved evidence is synthesized locally.
            return new ResearchAnswerService(openai, null);
        } catch (IllegalArgumentException invalidConfiguration) {
            return new ResearchAnswerService(null, null);
        }
    }
    private static String setting(String name) {
        String value = System.getProperty("jade." + name);
        if (value == null || value.isBlank()) value = System.getenv("JADE_" + name.toUpperCase(java.util.Locale.ROOT).replace('.', '_'));
        return value == null ? "" : value.strip();
    }
}
