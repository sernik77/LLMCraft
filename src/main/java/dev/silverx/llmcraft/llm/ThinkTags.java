package dev.silverx.llmcraft.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Some local models inline their reasoning as &lt;think&gt;...&lt;/think&gt;; move it out of the visible text. */
final class ThinkTags {
    private static final Pattern THINK = Pattern.compile("(?s)<think>(.*?)</think>");

    private ThinkTags() {}

    static String strip(ChatResponse r) {
        String text = r.text == null ? "" : r.text;
        Matcher m = THINK.matcher(text);
        StringBuilder thinking = new StringBuilder(r.thinking == null ? "" : r.thinking);
        boolean found = false;
        while (m.find()) {
            thinking.append(m.group(1).trim()).append('\n');
            found = true;
        }
        if (!found) return text.trim();
        r.thinking = thinking.toString().trim();
        return m.replaceAll("").trim();
    }
}
