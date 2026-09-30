package com.jade.services.answer;
import com.jade.api.*;
import java.net.*;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import javax.swing.text.MutableAttributeSet;
import java.io.StringReader;
/** No-key retrieval of a small official-source set. Not a general search engine or crawler. */
public final class PublicWebRetrieval implements WebSearchProvider {
    private static final Set<String> HOSTS = Set.of("jdk.java.net", "openjdk.org", "maven.apache.org", "www.oracle.com", "docs.oracle.com");
    private final ProviderHttp.Transport transport;
    private final java.util.function.Function<String,InetAddress[]> resolver;
    public PublicWebRetrieval() { this(ProviderHttp::send, host -> { try { return InetAddress.getAllByName(host); } catch (Exception failure) { return new InetAddress[0]; } }); }
    PublicWebRetrieval(ProviderHttp.Transport transport, java.util.function.Function<String,InetAddress[]> resolver) { this.transport = transport; this.resolver = resolver; }
    public List<Hit> search(Query query, CancellationToken token) throws ServiceException {
        ResearchAnswerService.check(token);
        String text = query.text().toLowerCase(Locale.ROOT);
        List<URI> urls = new ArrayList<>();
        var explicit = java.util.regex.Pattern.compile("https?://[^\\s]+", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(query.text());
        while (explicit.find() && urls.size() < query.maxResults()) {
            try { urls.add(URI.create(explicit.group())); } catch (IllegalArgumentException invalid) { throw ProviderHttp.retrievalUnavailable(); }
        }
        if (urls.isEmpty() && text.matches("(?s).*\\bjava\\b.*") && text.matches("(?s).*\\b(release|version|stable|latest)\\b.*"))
            urls = List.of(URI.create("https://jdk.java.net/"), URI.create("https://www.oracle.com/java/technologies/java-se-support-roadmap.html"));
        if (urls.isEmpty() && text.matches("(?s).*\\bmaven\\b.*") && text.matches("(?s).*\\b(release|version|stable|latest|news)\\b.*"))
            urls = List.of(URI.create("https://maven.apache.org/download.cgi"));
        if (urls.isEmpty()) throw ProviderHttp.retrievalUnavailable();
        List<Hit> hits = new ArrayList<>();
        for (URI uri : urls.stream().distinct().limit(query.maxResults()).toList()) {
            ResearchAnswerService.check(token); validate(uri);
            try {
                byte[] bytes = transport.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                        .header("Accept", "text/html,text/plain").header("User-Agent", "JADE/0.1 bounded-public-retrieval").GET().build(), token);
                if (bytes.length > ProviderHttp.MAX_BYTES) throw new IllegalArgumentException();
                Hit hit = extract(uri, new String(bytes, StandardCharsets.UTF_8));
                if (!hit.snippet().isBlank()) hits.add(hit);
            } catch (ServiceException failure) { if (failure.error().code() == ErrorCode.CANCELLED) throw failure;
            } catch (Exception invalidPage) { /* Failed pages never become fabricated evidence. */ }
        }
        if (hits.isEmpty()) throw ProviderHttp.retrievalUnavailable();
        return List.copyOf(hits);
    }
    private void validate(URI uri) throws ServiceException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || !HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443 || uri.toString().length() > 2048)
            throw ProviderHttp.retrievalUnavailable();
        InetAddress[] addresses = resolver.apply(uri.getHost());
        if (addresses.length == 0) throw ProviderHttp.retrievalUnavailable();
        for (var address : addresses) {
            byte[] bytes = address.getAddress(); int first = bytes[0] & 255, second = bytes.length > 1 ? bytes[1] & 255 : 0;
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || bytes.length == 4 && (first == 0 || first >= 224 || first == 100 && second >= 64 && second <= 127 || first == 198 && (second == 18 || second == 19))
                    || bytes.length == 16 && ((first & 0xe0) != 0x20 || first == 0x20 && second == 0x02)) throw ProviderHttp.retrievalUnavailable();
        }
        // Only fixed, operator-controlled official hosts are permitted; no user-controlled DNS hosts or redirects.
    }
    static Hit extract(URI uri, String html) throws Exception {
        StringBuilder title = new StringBuilder(), content = new StringBuilder();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            int hidden; boolean inTitle;
            public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE || tag == HTML.Tag.HEAD) hidden++;
                if (tag == HTML.Tag.TITLE) inTitle = true;
            }
            public void handleEndTag(HTML.Tag tag, int position) {
                if (tag == HTML.Tag.TITLE) inTitle = false;
                if ((tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE || tag == HTML.Tag.HEAD) && hidden > 0) hidden--;
            }
            public void handleText(char[] data, int position) {
                if (inTitle && title.length() < 200) title.append(data, 0, Math.min(data.length, 200-title.length()));
                if (hidden == 0 && content.length() < 1000) { content.append(data,0,Math.min(data.length,1000-content.length())); if (content.length()<1000) content.append(' '); }
            }
        }, true);
        return new Hit(title.isEmpty() ? uri.getHost() : title.toString().strip(), uri, content.toString().replaceAll("\\s+", " ").strip());
    }
}
