package com.qalab.qalabai.tool.browser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DomSimplifier {

    private static final Logger log = LoggerFactory.getLogger(DomSimplifier.class);

    public String simplify(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        log.debug("Simplifying HTML, original length: {} chars", html.length());

        Document doc = Jsoup.parse(html);

        doc.select("script, style, svg, noscript, iframe, link[rel='stylesheet'], meta").remove();

        doc.select("[style]").removeAttr("style");
        doc.select("[data-reactid]").removeAttr("data-reactid");
        doc.select("[class]").removeAttr("class");
        doc.select("[id]").removeAttr("id");

        doc.select("[aria-hidden='true'], [hidden], .hidden, .sr-only").remove();

        Elements important = doc.select(
                "form, input, button, a, table, nav, header, footer, main, section, article, h1, h2, h3, h4, h5, h6, label, select, textarea"
        );

        StringBuilder sb = new StringBuilder();
        for (Element el : important) {
            String tag = el.tagName();
            String text = el.ownText().trim();
            String type = el.attr("type");
            String name = el.attr("name");
            String placeholder = el.attr("placeholder");
            String href = el.attr("href");
            String role = el.attr("role");

            sb.append("<").append(tag);
            if (!type.isEmpty()) sb.append(" type=\"").append(type).append("\"");
            if (!name.isEmpty()) sb.append(" name=\"").append(name).append("\"");
            if (!placeholder.isEmpty()) sb.append(" placeholder=\"").append(placeholder).append("\"");
            if (!href.isEmpty()) sb.append(" href=\"").append(href).append("\"");
            if (!role.isEmpty()) sb.append(" role=\"").append(role).append("\"");
            sb.append(">");
            if (!text.isEmpty()) sb.append(text);
            sb.append("</").append(tag).append(">\n");
        }

        String simplified = sb.toString();
        log.debug("Simplified HTML length: {} chars", simplified.length());

        return simplified;
    }
}
