package com.example.geminilegacy;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny Markdown renderer for Gemini replies: headings, bold, italic, bold-italic, strikethrough,
 * inline code, fenced code blocks, bullet and numbered lists, block quotes, links, rules and tables.
 * Unmatched markers (e.g. while a reply is still streaming) stay as plain text.
 *
 * parse() returns a list of blocks: CharSequence (styled text) or Table.
 */
public final class Markdown {

    public static final class Table {
        public final List<CharSequence[]> rows = new ArrayList<CharSequence[]>(); // rows.get(0) is the header
        public int[] align;                                                     // 0 left, 1 center, 2 right
    }

    /** A fenced ``` code block, shown as its own scrollable card with a Copy button. */
    public static final class CodeBlock {
        public final String lang;
        public final String code;

        CodeBlock(String lang, String code) {
            this.lang = lang;
            this.code = code;
        }
    }

    private static final Pattern HEADING =Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^([-*_])(\\s*\\1){2,}$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|?[\\s:|-]*-[\\s:|-]*\\|?$");
    private static final Pattern CELL_SPLIT = Pattern.compile("(?<!\\\\)\\|");
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\((https?://[^\\s)]+)[^)]*\\)");
    private static final Pattern BARE_IMAGE = Pattern.compile(
            "https?://[^\\s)\\]>\"'<]+\\.(?:png|jpe?g|gif|webp|bmp)(?:\\?[^\\s)\\]>\"'<]*)?",
            Pattern.CASE_INSENSITIVE);
    private static final float[] HEADING_SCALE = {1.5f, 1.3f, 1.15f, 1.05f, 1f, 1f};
    private static final int FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

    private Markdown() {}

    /** Image URLs found in the text: ![alt](url) and bare links ending in an image extension. */
    public static List<String> imageUrls(String text, int max) {
        Set<String> found = new LinkedHashSet<String>();
        Matcher m = MD_IMAGE.matcher(text);
        while (m.find() && found.size() < max) found.add(m.group(1));
        m = BARE_IMAGE.matcher(text);
        while (m.find() && found.size() < max) found.add(m.group());
        return new ArrayList<String>(found);
    }

    /** Flat rendering (tables become plain rows); used for thought summaries. */
    public static CharSequence render(String src) {
        SpannableStringBuilder all = new SpannableStringBuilder();
        for (Object b : parse(src)) {
            if (all.length() > 0) all.append('\n');
            if (b instanceof CodeBlock) {
                all.append(((CodeBlock) b).code);
            } else if (b instanceof Table) {
                for (CharSequence[] row : ((Table) b).rows) {
                    for (int c = 0; c < row.length; c++) {
                        if (c > 0) all.append("  |  ");
                        all.append(row[c]);
                    }
                    all.append('\n');
                }
            } else {
                all.append((CharSequence) b);
            }
        }
        return all;
    }

    public static List<Object> parse(String src) {
        List<Object> blocks = new ArrayList<Object>();
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = src.replace("\r\n", "\n").split("\n", -1);
        boolean inCode = false;
        String codeLang = "";
        StringBuilder code = new StringBuilder();
        boolean lastBlank = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                if (!inCode) {
                    inCode = true;
                    codeLang = trimmed.substring(3).trim();
                    code.setLength(0);
                    trimTrailingNewlines(out);
                    if (out.length() > 0) blocks.add(out);
                    out = new SpannableStringBuilder();
                } else {
                    inCode = false;
                    blocks.add(new CodeBlock(codeLang, code.toString()));
                    lastBlank = false;
                }
                continue;
            }
            if (inCode) {
                if (code.length() > 0) code.append('\n');
                code.append(line);
                if (code.length() == 0) code.append(' '); // keep leading blank lines of the block
                continue;
            }
            if (trimmed.length() == 0) {
                if (!lastBlank && out.length() > 0) out.append('\n');
                lastBlank = true;
                continue;
            }
            lastBlank = false;

            // Table: a row with pipes immediately followed by a |---|---| separator row.
            if (trimmed.indexOf('|') >= 0 && i + 1 < lines.length && isSeparator(lines[i + 1].trim())) {
                Table table = new Table();
                table.rows.add(cells(trimmed));
                table.align = alignments(lines[i + 1].trim());
                int j = i + 2;
                while (j < lines.length && lines[j].trim().length() > 0 && lines[j].indexOf('|') >= 0) {
                    table.rows.add(cells(lines[j].trim()));
                    j++;
                }
                trimTrailingNewlines(out);
                if (out.length() > 0) blocks.add(out);
                out = new SpannableStringBuilder();
                blocks.add(table);
                i = j - 1;
                continue;
            }

            Matcher m;
            if (isSeparator(trimmed)) {
                continue; // stray table underline
            } else if ((m = HEADING.matcher(trimmed)).matches()) {
                int level = m.group(1).length();
                int st = out.length();
                inline(out, m.group(2));
                out.setSpan(new StyleSpan(Typeface.BOLD), st, out.length(), FLAGS);
                out.setSpan(new RelativeSizeSpan(HEADING_SCALE[level - 1]), st, out.length(), FLAGS);
                out.append('\n');
            } else if (RULE.matcher(trimmed).matches()) {
                out.append("————————\n");
            } else if ((m = BULLET.matcher(line)).matches()) {
                int level = m.group(1).length() / 2;
                for (int k = 0; k < level; k++) out.append("    ");
                out.append("• ");
                inline(out, m.group(2));
                out.append('\n');
            } else if ((m = NUMBERED.matcher(line)).matches()) {
                int level = m.group(1).length() / 2;
                for (int k = 0; k < level; k++) out.append("    ");
                out.append(m.group(2)).append(". ");
                inline(out, m.group(3));
                out.append('\n');
            } else if ((m = QUOTE.matcher(line)).matches()) {
                int st = out.length();
                inline(out, m.group(1));
                out.append('\n');
                out.setSpan(new QuoteSpan(0xFF8AB4F8), st, out.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            } else {
                inline(out, line);
                out.append('\n');
            }
        }
        if (inCode) blocks.add(new CodeBlock(codeLang, code.toString())); // still streaming
        trimTrailingNewlines(out);
        if (out.length() > 0) blocks.add(out);
        return blocks;
    }

    /** Styled text for one table cell. */
    private static CharSequence renderInline(String s) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        inline(b, s);
        return b;
    }

    private static boolean isSeparator(String t) {
        return t.indexOf('|') >= 0 && t.indexOf('-') >= 0 && TABLE_SEPARATOR.matcher(t).matches();
    }

    private static CharSequence[] cells(String row) {
        String r = row;
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.substring(0, r.length() - 1);
        String[] parts = CELL_SPLIT.split(r, -1);
        CharSequence[] out = new CharSequence[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = renderInline(parts[i].trim());
        return out;
    }

    private static int[] alignments(String separator) {
        String r = separator;
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        String[] parts = r.split("\\|", -1);
        int[] a = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            boolean left = p.startsWith(":");
            boolean right = p.endsWith(":");
            a[i] = left && right ? 1 : (right ? 2 : 0);
        }
        return a;
    }

    private static void trimTrailingNewlines(SpannableStringBuilder out) {
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
    }

    private static void code(SpannableStringBuilder out, String text) {
        int st = out.length();
        out.append(text);
        out.setSpan(new TypefaceSpan("monospace"), st, out.length(), FLAGS);
        out.setSpan(new RelativeSizeSpan(0.9f), st, out.length(), FLAGS);
        out.setSpan(new BackgroundColorSpan(0x33FFFFFF), st, out.length(), FLAGS);
    }

    private static void inline(SpannableStringBuilder out, String s) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);

            if (c == '\\' && i + 1 < n && "\\`*_{}[]()#+-.!~>|".indexOf(s.charAt(i + 1)) >= 0) {
                out.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int j = s.indexOf('`', i + 1);
                if (j > i + 1) {
                    code(out, s.substring(i + 1, j));
                    i = j + 1;
                    continue;
                }
            }
            if (s.startsWith("***", i)) {
                int j = s.indexOf("***", i + 3);
                if (j > i + 3) {
                    wrap(out, s.substring(i + 3, j), new StyleSpan(Typeface.BOLD_ITALIC));
                    i = j + 3;
                    continue;
                }
            }
            if (s.startsWith("**", i)) {
                int j = s.indexOf("**", i + 2);
                if (j > i + 2) {
                    wrap(out, s.substring(i + 2, j), new StyleSpan(Typeface.BOLD));
                    i = j + 2;
                    continue;
                }
            }
            if (s.startsWith("~~", i)) {
                int j = s.indexOf("~~", i + 2);
                if (j > i + 2) {
                    wrap(out, s.substring(i + 2, j), new StrikethroughSpan());
                    i = j + 2;
                    continue;
                }
            }
            if (c == '*' && i + 1 < n && !Character.isWhitespace(s.charAt(i + 1)) && s.charAt(i + 1) != '*') {
                int j = findSingle(s, '*', i + 1);
                if (j > i + 1 && !Character.isWhitespace(s.charAt(j - 1))) {
                    wrap(out, s.substring(i + 1, j), new StyleSpan(Typeface.ITALIC));
                    i = j + 1;
                    continue;
                }
            }
            if (c == '_' && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                boolean bold = s.startsWith("__", i);
                int open = bold ? 2 : 1;
                if (i + open < n && !Character.isWhitespace(s.charAt(i + open))) {
                    int j = bold ? s.indexOf("__", i + 2) : findSingle(s, '_', i + 1);
                    if (j > i + open && !Character.isWhitespace(s.charAt(j - 1))
                            && (j + open >= n || !Character.isLetterOrDigit(s.charAt(j + open)))) {
                        wrap(out, s.substring(i + open, j),
                                new StyleSpan(bold ? Typeface.BOLD : Typeface.ITALIC));
                        i = j + open;
                        continue;
                    }
                }
            }
            if (c == '[' || (c == '!' && i + 1 < n && s.charAt(i + 1) == '[')) {
                boolean image = c == '!';
                int end = link(out, s, image ? i + 1 : i, image);
                if (end > 0) {
                    i = end;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
    }

    /** Parses [label](url) starting at '[' index; returns the index after ')' or -1. */
    private static int link(SpannableStringBuilder out, String s, int open, boolean image) {
        int mid = s.indexOf("](", open + 1);
        if (mid < 0) return -1;
        int end = s.indexOf(')', mid + 2);
        if (end < 0) return -1;
        String label = s.substring(open + 1, mid);
        String url = s.substring(mid + 2, end).trim();
        if (label.indexOf('[') >= 0 || url.length() == 0) return -1;
        int sp = url.indexOf(' ');
        if (sp > 0) url = url.substring(0, sp); // drop an optional "title"
        if (label.length() == 0) label = image ? "image" : url;
        int st = out.length();
        out.append(label);
        out.setSpan(new URLSpan(url), st, out.length(), FLAGS);
        return end + 1;
    }

    private static void wrap(SpannableStringBuilder out, String inner, Object span) {
        int st = out.length();
        inline(out, inner);
        out.setSpan(span, st, out.length(), FLAGS);
    }

    /** Finds the next lone marker character, skipping doubled ones (so "*a **b** c*" works). */
    private static int findSingle(String s, char ch, int from) {
        int n = s.length();
        for (int j = from; j < n; j++) {
            if (s.charAt(j) != ch) continue;
            if (j + 1 < n && s.charAt(j + 1) == ch) {
                j++;
                continue;
            }
            return j;
        }
        return -1;
    }
}
