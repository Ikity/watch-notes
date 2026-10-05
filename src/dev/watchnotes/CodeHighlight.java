package dev.watchnotes;

import java.util.*;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

/**
 * Language-aware coloring for fenced code blocks.
 *
 * <p>Colors are emitted as {@code <font color>} so both the phone WebView and
 * Android {@code Html.fromHtml} on the watch render them without JavaScript.
 * Code is tokenized from its decoded text and rebuilt with escaped nodes, so
 * {@code <} in source can never become markup.</p>
 */
public final class CodeHighlight {
    static final String COMMENT = "#6a9955";
    static final String STRING = "#ce9178";
    static final String KEYWORD = "#569cd9";
    static final String NUMBER = "#b5cea8";
    static final String VARIABLE = "#4ec9b0";

    private CodeHighlight() { }

    static final class Span {
        final String text; final String color;
        Span(String text, String color) { this.text = text; this.color = color; }
    }

    public static void highlightBlocks(Document fragment) {
        for (Element code : fragment.select("pre code")) {
            String lang = language(code);
            if (lang == null) continue;
            String raw = code.text();
            if (raw.isEmpty()) continue;
            List<Span> spans = tokenize(raw, lang);
            if (spans == null) continue;
            code.empty();
            TextNode plain = null;
            for (Span span : spans) {
                if (span.color == null) {
                    // Merge consecutive plain runs into one text node.
                    if (plain == null) { plain = new TextNode(span.text); code.appendChild(plain); }
                    else plain.text(plain.getWholeText() + span.text);
                } else {
                    plain = null;
                    code.appendElement("font").attr("color", span.color).text(span.text);
                }
            }
        }
    }

    static String language(Element code) {
        for (String part : code.attr("class").split("\\s+")) {
            if (part.startsWith("language-") && part.length() > 9)
                return part.substring(9).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    static List<Span> tokenize(String code, String lang) {
        switch (lang) {
            case "java": return cLike(code, JAVA, false);
            case "c": case "h": return cLike(code, C, false);
            case "cpp": case "c++": case "hpp": case "arduino": case "ino": return cLike(code, CPP, false);
            case "cs": case "csharp": return cLike(code, CSHARP, false);
            case "js": case "javascript": case "ts": case "typescript": return cLike(code, JS, true);
            case "go": case "rust": case "kotlin": case "swift": return cLike(code, CSHARP, false);
            case "py": case "python": return python(code);
            case "sh": case "bash": case "shell": case "zsh": return bash(code);
            case "sql": return sql(code);
            case "json": return json(code);
            case "html": case "xml": return markup(code);
            default: return null;
        }
    }

    private static Set<String> set(String... words) {
        return new HashSet<>(Arrays.asList(words));
    }

    private static final Set<String> C = set("break", "case", "char", "const", "continue", "default", "do",
        "double", "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
        "register", "return", "short", "signed", "sizeof", "static", "struct", "switch", "typedef",
        "union", "unsigned", "void", "volatile", "while", "true", "false", "NULL");
    private static final Set<String> CPP = set("break", "case", "catch", "char", "class", "const",
        "constexpr", "continue", "default", "delete", "do", "double", "else", "enum", "explicit",
        "export", "extern", "false", "float", "for", "friend", "goto", "if", "inline", "int", "long",
        "namespace", "new", "nullptr", "operator", "private", "protected", "public", "return",
        "short", "signed", "sizeof", "static", "struct", "switch", "template", "this", "throw",
        "true", "try", "typedef", "typename", "union", "unsigned", "using", "virtual", "void",
        "volatile", "while", "string", "vector", "map", "cout", "cin", "endl", "auto", "bool",
        "setup", "loop", "pinMode", "digitalWrite", "digitalRead", "analogRead", "analogWrite",
        "delay", "delayMicroseconds", "Serial", "HIGH", "LOW", "INPUT", "OUTPUT", "INPUT_PULLUP",
        "LED_BUILTIN", "byte", "word", "boolean");
    private static final Set<String> JAVA = set("abstract", "assert", "boolean", "break", "byte",
        "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else",
        "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import",
        "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected",
        "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null",
        "var", "record", "sealed", "String", "System", "out", "println");
    private static final Set<String> CSHARP = set("abstract", "as", "base", "bool", "break", "byte",
        "case", "catch", "char", "checked", "class", "const", "continue", "decimal", "default", "do",
        "double", "else", "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float",
        "for", "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is",
        "lock", "long", "namespace", "new", "null", "object", "operator", "out", "override", "params",
        "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed", "short",
        "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true", "try",
        "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using", "virtual", "void",
        "volatile", "while", "var", "dynamic", "func", "package", "fn", "let", "mut", "match", "val");
    private static final Set<String> JS = set("break", "case", "catch", "class", "const", "continue",
        "debugger", "default", "delete", "do", "else", "export", "extends", "finally", "for",
        "function", "if", "import", "in", "instanceof", "let", "new", "return", "super", "switch",
        "this", "throw", "try", "typeof", "var", "void", "while", "with", "yield", "true", "false",
        "null", "undefined", "async", "await", "static", "get", "set", "type", "interface",
        "implements", "enum", "declare", "abstract", "readonly", "console", "log");
    private static final Set<String> PYTHON = set("False", "None", "True", "and", "as", "assert",
        "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except",
        "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not",
        "or", "pass", "raise", "return", "try", "while", "with", "yield", "print", "len", "range",
        "self", "int", "str", "list", "dict");
    private static final Set<String> BASH = set("if", "then", "else", "elif", "fi", "for", "while",
        "until", "do", "done", "case", "esac", "function", "select", "in", "echo", "printf", "cd",
        "ls", "mkdir", "rm", "cp", "mv", "touch", "cat", "grep", "sed", "awk", "find", "chmod",
        "chown", "export", "local", "readonly", "return", "exit", "shift", "trap", "source", "alias",
        "test", "true", "false", "do", "time", "command", "sudo", "ffmpeg");
    private static final Set<String> SQL = set("select", "from", "where", "and", "or", "not", "null",
        "insert", "into", "values", "update", "set", "delete", "create", "table", "alter", "drop",
        "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having", "limit",
        "offset", "union", "distinct", "as", "in", "is", "like", "between", "exists", "case", "when",
        "then", "else", "end", "primary", "key", "foreign", "references", "index", "view", "all");

    private static boolean isWord(char c) { return c == '_' || Character.isLetterOrDigit(c); }

    private static List<Span> cLike(String code, Set<String> keywords, boolean template) {
        List<Span> out = new ArrayList<>();
        int i = 0, n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            if (c == '/' && i + 1 < n && code.charAt(i + 1) == '/') {
                int j = code.indexOf('\n', i);
                if (j < 0) j = n;
                out.add(new Span(code.substring(i, j), COMMENT)); i = j;
            } else if (c == '/' && i + 1 < n && code.charAt(i + 1) == '*') {
                int j = code.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                out.add(new Span(code.substring(i, j), COMMENT)); i = j;
            } else if (c == '"' || c == '\'' || (template && c == '`')) {
                int j = i + 1;
                while (j < n && code.charAt(j) != c) { if (code.charAt(j) == '\\') j++; j++; }
                if (j < n) j++;
                out.add(new Span(code.substring(i, j), STRING)); i = j;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(code.charAt(i + 1)))) {
                int j = i + 1;
                while (j < n && (isWord(code.charAt(j)) || code.charAt(j) == '.')) j++;
                out.add(new Span(code.substring(i, j), NUMBER)); i = j;
            } else if (isWord(c)) {
                int j = i + 1;
                while (j < n && isWord(code.charAt(j))) j++;
                String word = code.substring(i, j);
                out.add(new Span(word, keywords.contains(word) ? KEYWORD : null)); i = j;
            } else { out.add(new Span(String.valueOf(c), null)); i++; }
        }
        return out;
    }

    private static List<Span> python(String code) {
        List<Span> out = new ArrayList<>();
        int i = 0, n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            if (c == '#') {
                int j = code.indexOf('\n', i);
                if (j < 0) j = n;
                out.add(new Span(code.substring(i, j), COMMENT)); i = j;
            } else if ((c == '"' || c == '\'') && i + 2 < n && code.charAt(i + 1) == c && code.charAt(i + 2) == c) {
                String fence = code.substring(i, i + 3);
                int j = code.indexOf(fence, i + 3);
                j = j < 0 ? n : j + 3;
                out.add(new Span(code.substring(i, j), STRING)); i = j;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && code.charAt(j) != c && code.charAt(j) != '\n') { if (code.charAt(j) == '\\') j++; j++; }
                if (j < n && code.charAt(j) == c) j++;
                out.add(new Span(code.substring(i, j), STRING)); i = j;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(code.charAt(i + 1)))) {
                int j = i + 1;
                while (j < n && (isWord(code.charAt(j)) || code.charAt(j) == '.')) j++;
                out.add(new Span(code.substring(i, j), NUMBER)); i = j;
            } else if (isWord(c)) {
                int j = i + 1;
                while (j < n && isWord(code.charAt(j))) j++;
                String word = code.substring(i, j);
                out.add(new Span(word, PYTHON.contains(word) ? KEYWORD : null)); i = j;
            } else { out.add(new Span(String.valueOf(c), null)); i++; }
        }
        return out;
    }

    private static List<Span> bash(String code) {
        List<Span> out = new ArrayList<>();
        int i = 0, n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            if (c == '#') {
                int j = code.indexOf('\n', i);
                if (j < 0) j = n;
                out.add(new Span(code.substring(i, j), COMMENT)); i = j;
            } else if (c == '"' || c == '\'' || c == '`') {
                int j = i + 1;
                while (j < n && code.charAt(j) != c) { if (c != '\'' && code.charAt(j) == '\\') j++; j++; }
                if (j < n) j++;
                out.add(new Span(code.substring(i, j), STRING)); i = j;
            } else if (c == '$' && i + 1 < n) {
                char d = code.charAt(i + 1);
                int j;
                if (d == '{' || d == '(') {
                    char open = d, close = d == '{' ? '}' : ')';
                    int depth = 0; j = i + 1;
                    while (j < n) {
                        if (code.charAt(j) == open) depth++;
                        else if (code.charAt(j) == close && --depth == 0) { j++; break; }
                        j++;
                    }
                } else if (isWord(d) || d == '?' || d == '#' || d == '@' || d == '*' || d == '$' || d == '!' || d == '-' || Character.isDigit(d)) {
                    j = i + 2;
                    while (j < n && isWord(code.charAt(j))) j++;
                } else { out.add(new Span("$", null)); i++; continue; }
                out.add(new Span(code.substring(i, j), VARIABLE)); i = j;
            } else if (Character.isDigit(c)) {
                int j = i + 1;
                while (j < n && (isWord(code.charAt(j)) || code.charAt(j) == '.')) j++;
                out.add(new Span(code.substring(i, j), NUMBER)); i = j;
            } else if (isWord(c)) {
                int j = i + 1;
                while (j < n && isWord(code.charAt(j))) j++;
                String word = code.substring(i, j);
                out.add(new Span(word, BASH.contains(word) ? KEYWORD : null)); i = j;
            } else { out.add(new Span(String.valueOf(c), null)); i++; }
        }
        return out;
    }

    private static List<Span> sql(String code) {
        List<Span> out = new ArrayList<>();
        int i = 0, n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            if (c == '-' && i + 1 < n && code.charAt(i + 1) == '-') {
                int j = code.indexOf('\n', i);
                if (j < 0) j = n;
                out.add(new Span(code.substring(i, j), COMMENT)); i = j;
            } else if (c == '/' && i + 1 < n && code.charAt(i + 1) == '*') {
                int j = code.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                out.add(new Span(code.substring(i, j), COMMENT)); i = j;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    if (code.charAt(j) == '\'' && j + 1 < n && code.charAt(j + 1) == '\'') { j += 2; continue; }
                    if (code.charAt(j) == '\'') { j++; break; }
                    j++;
                }
                out.add(new Span(code.substring(i, Math.min(j, n)), STRING)); i = Math.min(j, n);
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(code.charAt(i + 1)))) {
                int j = i + 1;
                while (j < n && (isWord(code.charAt(j)) || code.charAt(j) == '.')) j++;
                out.add(new Span(code.substring(i, j), NUMBER)); i = j;
            } else if (isWord(c)) {
                int j = i + 1;
                while (j < n && isWord(code.charAt(j))) j++;
                String word = code.substring(i, j);
                out.add(new Span(word, SQL.contains(word.toLowerCase(Locale.ROOT)) ? KEYWORD : null)); i = j;
            } else { out.add(new Span(String.valueOf(c), null)); i++; }
        }
        return out;
    }

    private static List<Span> json(String code) {
        List<Span> out = new ArrayList<>();
        int i = 0, n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < n && code.charAt(j) != '"') { if (code.charAt(j) == '\\') j++; j++; }
                if (j < n) j++;
                out.add(new Span(code.substring(i, j), STRING)); i = j;
            } else if (c == '-' || Character.isDigit(c)) {
                int j = i + 1;
                while (j < n && (isWord(code.charAt(j)) || code.charAt(j) == '.' || code.charAt(j) == '+' || code.charAt(j) == '-')) j++;
                out.add(new Span(code.substring(i, j), NUMBER)); i = j;
            } else if (isWord(c)) {
                int j = i + 1;
                while (j < n && isWord(code.charAt(j))) j++;
                String word = code.substring(i, j);
                out.add(new Span(word, word.equals("true") || word.equals("false") || word.equals("null") ? KEYWORD : null)); i = j;
            } else { out.add(new Span(String.valueOf(c), null)); i++; }
        }
        return out;
    }

    private static List<Span> markup(String code) {
        List<Span> out = new ArrayList<>();
        int i = 0, n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            if (c == '<' && code.startsWith("!--", i + 1)) {
                int j = code.indexOf("-->", i + 4);
                j = j < 0 ? n : j + 3;
                out.add(new Span(code.substring(i, j), COMMENT)); i = j;
            } else if (c == '<') {
                int j = i + 1;
                boolean inQuote = false; char quote = 0;
                while (j < n) {
                    char d = code.charAt(j);
                    if (inQuote) { if (d == quote) inQuote = false; }
                    else if (d == '"' || d == '\'') { inQuote = true; quote = d; }
                    else if (d == '>') break;
                    j++;
                }
                int end = j < n ? j + 1 : j;
                tagSpans(out, code.substring(i, end)); i = end;
            } else { out.add(new Span(String.valueOf(c), null)); i++; }
        }
        return out;
    }

    private static void tagSpans(List<Span> out, String tag) {
        int i = 0;
        out.add(new Span("<", null)); i++;
        if (i < tag.length() && tag.charAt(i) == '/') { out.add(new Span("/", null)); i++; }
        int nameStart = i;
        while (i < tag.length() && (Character.isLetterOrDigit(tag.charAt(i)) || tag.charAt(i) == '-'
                || tag.charAt(i) == ':' || tag.charAt(i) == '_')) i++;
        if (i > nameStart) out.add(new Span(tag.substring(nameStart, i), KEYWORD));
        StringBuilder rest = new StringBuilder();
        while (i < tag.length()) {
            char c = tag.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < tag.length() && tag.charAt(j) != c) j++;
                if (j < tag.length()) j++;
                if (rest.length() > 0) { out.add(new Span(rest.toString(), null)); rest.setLength(0); }
                out.add(new Span(tag.substring(i, j), STRING)); i = j;
            } else { rest.append(c); i++; }
        }
        if (rest.length() > 0) out.add(new Span(rest.toString(), null));
    }
}
