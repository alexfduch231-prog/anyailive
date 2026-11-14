package ru.alexv.voicebridge.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Нарезка растущего текста ответа на куски, которые уже можно отдать в TTS.
 *
 * Зачем: TTS по целому ответу начинает говорить только когда модель договорила,
 * а по предложениям — почти сразу, поэтому первый звук приходит на секунды раньше.
 *
 * Правила:
 * - жёсткие границы (. ! ? … и перевод строки) режут сразу;
 * - мягкие (; :) режут только если слева уже набралось minChars символов,
 *   иначе получаются обрывки вроде «Однако:»;
 * - точка внутри числа (3.14) границей не считается;
 * - хвост без знака конца возвращается в {@link #finish()}.
 *
 * Класс чистый Java (без android.*) и полностью проверяется на JVM.
 */
public final class Sentences {

    public static final int DEFAULT_MIN_CHARS = 25;

    private static final String[] EMPTY = new String[0];

    private final int minChars;
    private final StringBuilder acc = new StringBuilder();

    public Sentences() {
        this(DEFAULT_MIN_CHARS);
    }

    public Sentences(int minChars) {
        this.minChars = Math.max(1, minChars);
    }

    /** То, что накопилось и ещё не отдано как фраза. */
    public String pending() {
        return acc.toString().trim();
    }

    public void reset() {
        acc.setLength(0);
    }

    /**
     * Добавить очередную порцию текста.
     *
     * @return готовые фразы (может быть пусто)
     */
    public String[] feed(String text) {
        if (text == null || text.length() == 0) {
            return EMPTY;
        }
        acc.append(text);
        List<String> out = new ArrayList<String>();
        int n = acc.length();
        int from = 0;
        int i = 0;
        while (i < n) {
            char c = acc.charAt(i);
            if (c == '\n') {
                emit(out, from, i + 1);
                from = i + 1;
                i++;
                continue;
            }
            if (c == '.' || c == '!' || c == '?' || c == '\u2026') {
                if (c == '.' && isDecimal(i, n)) {
                    i++;
                    continue;
                }
                int j = i + 1;
                while (j < n && isTail(acc.charAt(j))) {
                    j++;
                }
                emit(out, from, j);
                from = j;
                i = j;
                continue;
            }
            if ((c == ';' || c == ':') && trimmedLength(from, i + 1) >= minChars) {
                emit(out, from, i + 1);
                from = i + 1;
            }
            i++;
        }
        if (from > 0) {
            acc.delete(0, from);
        }
        return out.isEmpty() ? EMPTY : out.toArray(new String[out.size()]);
    }

    /** Закончить накопление: отдать хвост, даже если он короткий. */
    public String[] finish() {
        String rest = acc.toString().trim();
        acc.setLength(0);
        if (rest.length() == 0 || stripMarkdown(rest).length() == 0) {
            return EMPTY;
        }
        return new String[]{rest};
    }

    /** Разовые фразы без хвоста — удобно для тестов и коротких ответов. */
    public static String[] split(String text) {
        return split(text, DEFAULT_MIN_CHARS);
    }

    public static String[] split(String text, int minChars) {
        return new Sentences(minChars).feed(text);
    }

    private void emit(List<String> out, int from, int to) {
        if (to <= from) {
            return;
        }
        String phrase = acc.substring(from, Math.min(to, acc.length())).trim();
        if (phrase.length() == 0) {
            return;
        }
        if (stripMarkdown(phrase).length() == 0) {
            return;
        }
        out.add(phrase);
    }

    private int trimmedLength(int from, int to) {
        return acc.substring(from, Math.min(to, acc.length())).trim().length();
    }

    private boolean isDecimal(int i, int n) {
        if (i <= 0 || i + 1 >= n) {
            return false;
        }
        return Character.isDigit(acc.charAt(i - 1)) && Character.isDigit(acc.charAt(i + 1));
    }

    private static boolean isTail(char c) {
        return c == '.' || c == '!' || c == '?' || c == '\u2026' || c == ')' || c == ']'
                || c == '"' || c == '\u00BB' || c == '\u201D' || c == '\'';
    }

    /**
     * Убрать из фразы то, что нельзя прочитать вслух: markdown-разметку, блоки кода,
     * ссылки (оставляем текст), html-теги и эмодзи.
     */
    public static String stripMarkdown(String s) {
        if (s == null) {
            return "";
        }
        String t = s;
        t = t.replaceAll("(?s)```.*?```", " ");
        t = t.replaceAll("(?s)~~~.*?~~~", " ");
        t = t.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("`+", "");
        t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        t = t.replaceAll("(?m)^\\s*[-*+\u2022]\\s+", "");
        t = t.replaceAll("(?m)^\\s*>\\s?", "");
        t = t.replaceAll("[*_~|]+", "");
        t = t.replaceAll("<[^>]{1,40}>", " ");
        t = dropEmoji(t);
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    /** Есть ли в строке что-то произносимое. */
    public static boolean speakable(String s) {
        return stripMarkdown(s).length() > 0;
    }

    private static String dropEmoji(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int width = Character.charCount(cp);
            if (!isEmoji(cp)) {
                b.appendCodePoint(cp);
            }
            i += width;
        }
        return b.toString();
    }

    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2B00 && cp <= 0x2BFF)
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || cp == 0x200D
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF);
    }
}
