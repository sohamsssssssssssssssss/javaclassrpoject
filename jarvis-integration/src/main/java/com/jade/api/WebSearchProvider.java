package com.jade.api;
import java.net.URI;
import java.util.List;
import java.util.Objects;
@FunctionalInterface
public interface WebSearchProvider {
    List<Hit> search(Query query, CancellationToken cancellation) throws ServiceException;
    /** Optional integrated research: one provider call can search and synthesize. */
    default java.util.Optional<KnowledgeAnswerProvider.Answer> research(GeneralQuestion question, CancellationToken cancellation) throws ServiceException {
        return java.util.Optional.empty();
    }
    record Query(String text, int maxResults) {
        public Query {
            Objects.requireNonNull(text);
            if (text.isBlank() || text.length() > 600 || maxResults < 1 || maxResults > 5)
                throw new IllegalArgumentException("Invalid search bounds");
        }
    }
    record Hit(String title, URI uri, String snippet) {
        public Hit {
            Objects.requireNonNull(title); Objects.requireNonNull(uri); Objects.requireNonNull(snippet);
            if (!List.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.toString().length() > 2048)
                throw new IllegalArgumentException("Invalid source URI");
            title = title.substring(0, Math.min(200, title.length()));
            snippet = snippet.substring(0, Math.min(1000, snippet.length()));
        }
    }
}
