package com.jade.services.answer;
import com.jade.api.*;
import java.util.Locale;
public final class QuestionRouter {
    private QuestionRouter() { }
    // ponytail: bounded lexical freshness policy; semantic routing belongs to a later loop.
    public static AnswerMode mode(GeneralQuestion question) {
        String text = question.originalText().toLowerCase(Locale.ROOT);
        return text.matches("(?s).*\\b(search the web|google|look up|latest|current|today|yesterday|recent|right now|this week|news|https?://)\\b.*")
                ? AnswerMode.WEB_RESEARCH : AnswerMode.KNOWLEDGE;
    }
    public static WebSearchProvider.Query query(GeneralQuestion question) {
        String text = question.originalText().strip().replaceFirst("(?i)^(search the web(?: for)?|google|look up)\\s+", "");
        return new WebSearchProvider.Query(text.substring(0, Math.min(600, text.length())), 5);
    }
}
