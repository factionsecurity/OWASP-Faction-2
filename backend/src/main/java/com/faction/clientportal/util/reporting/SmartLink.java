package com.faction.clientportal.util.reporting;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits the plain string held by a {@code HYPERLINK} user-defined field into the runs of text and
 * links a report should show.
 *
 * <p>The field stores one line — whatever the assessor typed. It is usually a single address, but
 * just as often a short list: three email addresses for the contacts section, two portal URLs.
 * {@link #parse} keeps that line intact, delimiters and all, and marks only the parts that are
 * genuinely addressable.
 *
 * <p>What counts as addressable is deliberately narrow:
 * <ul>
 *   <li>an email address → {@code mailto:}</li>
 *   <li>an explicit {@code http://} or {@code https://} URL → used as typed</li>
 *   <li>a bare hostname such as {@code acme.com} or {@code www.acme.com/security} → {@code https://}</li>
 * </ul>
 * Everything else — "N/A", a person's name, "version 1.2" — stays plain text. The alternative,
 * linking whatever is there, produces reports full of {@code https://N/A}: nothing looks wrong
 * until a client clicks one.
 *
 * <p>The display text is always what the user typed. This type has no "label plus target" form on
 * purpose; a single value that is both is the whole point of the field.
 */
public final class SmartLink {

    private SmartLink() {}

    /**
     * One piece of the value. {@code href} is null for text that is not a link, which is the only
     * difference between the two kinds — the {@code text} is verbatim either way, so concatenating
     * every segment's text reproduces the original value exactly.
     */
    public record Segment(String text, String href) {

        public boolean isLink() {
            return href != null && !href.isEmpty();
        }

        static Segment plain(String text) {
            return new Segment(text, null);
        }
    }

    /** Runs of commas, semicolons and whitespace separating one address from the next. */
    private static final Pattern DELIMITER = Pattern.compile("[,;\\s]+");

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?"
            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?)*\\.[A-Za-z]{2,}$");

    private static final Pattern EXPLICIT_URL = Pattern.compile(
            "^https?://\\S+$", Pattern.CASE_INSENSITIVE);

    /**
     * A hostname with no scheme, optionally followed by a port and a path. The final label must be
     * two or more letters, which is what separates {@code acme.io} from {@code version 1.2} and
     * from an IP address someone pasted in.
     */
    private static final Pattern BARE_HOST = Pattern.compile(
            "^(?:[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?\\.)+[A-Za-z]{2,}"
            + "(?::\\d+)?(?:[/?#]\\S*)?$");

    /** Punctuation that belongs to the sentence rather than to the address it follows. */
    private static final String LEADING_PUNCTUATION  = "([{<\"'";
    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}>\"'";

    /**
     * Breaks {@code value} into text and link segments, preserving every character of the input.
     *
     * @return the segments in order; empty for a null or empty value
     */
    public static List<Segment> parse(String value) {
        if (value == null || value.isEmpty()) return List.of();

        List<Segment> segments = new ArrayList<>();
        Matcher delimiters = DELIMITER.matcher(value);
        int pos = 0;
        while (delimiters.find()) {
            if (delimiters.start() > pos) {
                addCandidate(segments, value.substring(pos, delimiters.start()));
            }
            segments.add(Segment.plain(delimiters.group()));
            pos = delimiters.end();
        }
        if (pos < value.length()) {
            addCandidate(segments, value.substring(pos));
        }
        return mergeAdjacentText(segments);
    }

    /**
     * The value rendered as HTML, for a {@code HYPERLINK} field referenced from inside a rich-text
     * field. The XHTML importer turns the anchors into real Word hyperlinks on the way into the
     * DOCX, so this path and the one building {@code w:hyperlink} directly agree on what the
     * reader ends up seeing.
     *
     * <p>Everything is escaped: the value is user input landing in a markup document, and a stray
     * {@code &} in a query string is enough to make the XHTML importer reject the whole field.
     */
    public static String toHtml(String value) {
        StringBuilder html = new StringBuilder();
        for (Segment segment : parse(value)) {
            if (segment.isLink()) {
                html.append("<a href=\"").append(escape(segment.href())).append("\">")
                    .append(escape(segment.text())).append("</a>");
            } else {
                html.append(escape(segment.text()));
            }
        }
        return html.toString();
    }

    /** Splits one whitespace-free chunk into its surrounding punctuation and the address inside. */
    private static void addCandidate(List<Segment> segments, String chunk) {
        int start = 0;
        while (start < chunk.length() && LEADING_PUNCTUATION.indexOf(chunk.charAt(start)) >= 0) {
            start++;
        }
        int end = chunk.length();
        while (end > start && TRAILING_PUNCTUATION.indexOf(chunk.charAt(end - 1)) >= 0) {
            end--;
        }

        String core = chunk.substring(start, end);
        String href = hrefFor(core);
        if (href == null) {
            segments.add(Segment.plain(chunk));
            return;
        }

        if (start > 0) segments.add(Segment.plain(chunk.substring(0, start)));
        segments.add(new Segment(core, href));
        if (end < chunk.length()) segments.add(Segment.plain(chunk.substring(end)));
    }

    /** Where this text points, or null if it is not an address. */
    private static String hrefFor(String core) {
        if (core.isEmpty()) return null;
        if (EMAIL.matcher(core).matches())        return "mailto:" + core;
        if (EXPLICIT_URL.matcher(core).matches()) return core;
        if (BARE_HOST.matcher(core).matches())    return "https://" + core;
        return null;
    }

    /**
     * Joins neighbouring plain segments. Purely to keep the DOCX tidy: every segment becomes its
     * own run, and a sentence split into one run per word is harder to read in the XML and easy to
     * mistake for a bug when someone opens the generated file.
     */
    private static List<Segment> mergeAdjacentText(List<Segment> segments) {
        List<Segment> merged = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            int last = merged.size() - 1;
            if (!segment.isLink() && last >= 0 && !merged.get(last).isLink()) {
                merged.set(last, Segment.plain(merged.get(last).text() + segment.text()));
            } else {
                merged.add(segment);
            }
        }
        return merged;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
