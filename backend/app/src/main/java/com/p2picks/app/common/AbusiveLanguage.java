package com.p2picks.app.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Refuses slurs and hate speech in text other people see (domain rules, "Abusive language filter"): chat messages,
 * polls, U2Pick questions and options, table names and usernames. Swearing and trash talk are allowed (owner
 * decision, 2026-11-20). The terms are in {@code moderation/abusive-terms.txt}.
 *
 * <p>Text is normalized before matching: accents and invisible characters dropped, look-alike letters from other
 * alphabets and symbol swaps ({@code n1gg@}) turned into letters, and runs of single letters ({@code f a g},
 * {@code n.i.g.g.e.r}) joined. Terms then match whole words, with stretched letters (never fewer than the term has)
 * and a plural s or z; terms marked {@code *} in the file also match inside run-together words.
 */
public final class AbusiveLanguage {
    private AbusiveLanguage() {}

    /** What callers send back with a 400 {@code abusive_language}. */
    public static final String MESSAGE = "That has language that isn't allowed here.";

    private record Term(List<Pattern> words, boolean inside) {}

    private static final List<Term> TERMS = load();
    private static final Pattern NOT_LETTERS = Pattern.compile("[^a-z]+");
    private static final Pattern MARKS = Pattern.compile("[\\p{M}\\p{Cf}]");
    private static final Map<Character, Character> SWAPS = Map.ofEntries(
            Map.entry('0', 'o'), Map.entry('1', 'i'), Map.entry('3', 'e'), Map.entry('4', 'a'),
            Map.entry('5', 's'), Map.entry('7', 't'), Map.entry('8', 'b'), Map.entry('9', 'g'),
            Map.entry('@', 'a'), Map.entry('$', 's'), Map.entry('!', 'i'), Map.entry('|', 'i'),
            // Cyrillic and Greek letters that look Latin.
            Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'), Map.entry('р', 'p'),
            Map.entry('с', 'c'), Map.entry('у', 'y'), Map.entry('х', 'x'), Map.entry('і', 'i'),
            Map.entry('ј', 'j'), Map.entry('к', 'k'), Map.entry('м', 'm'), Map.entry('т', 't'),
            Map.entry('н', 'h'), Map.entry('в', 'b'), Map.entry('α', 'a'), Map.entry('ο', 'o'),
            Map.entry('ι', 'i'), Map.entry('κ', 'k'), Map.entry('ν', 'v'), Map.entry('ρ', 'p'));

    /** Whether {@code text} contains a listed slur. Null or blank text is clean. */
    public static boolean contains(String text) {
        if (text == null || text.isBlank()) return false;
        List<String> words = words(text);
        if (words.isEmpty()) return false;
        String joined = String.join("", words);
        for (Term term : TERMS) {
            if (term.inside() && term.words().size() == 1 && term.words().getFirst().matcher(joined).find()) {
                return true;
            }
            if (matchesWords(term, words)) return true;
        }
        return false;
    }

    /** Whether any of {@code texts} contains a listed slur. */
    public static boolean containsAny(Iterable<String> texts) {
        for (String text : texts) if (contains(text)) return true;
        return false;
    }

    /** The text as lowercase letter-only words, with runs of single letters joined ("f a g" → "fag"). */
    static List<String> words(String text) {
        String decomposed = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKD)).replaceAll("");
        StringBuilder swapped = new StringBuilder(decomposed.length());
        for (char c : decomposed.toLowerCase(Locale.ROOT).toCharArray()) swapped.append(SWAPS.getOrDefault(c, c));
        List<String> words = new ArrayList<>();
        StringBuilder singles = new StringBuilder();
        for (String word : NOT_LETTERS.split(swapped)) {
            if (word.isEmpty()) continue;
            if (word.length() == 1) {
                singles.append(word);
                continue;
            }
            if (!singles.isEmpty()) {
                words.add(singles.toString());
                singles.setLength(0);
            }
            words.add(word);
        }
        if (!singles.isEmpty()) words.add(singles.toString());
        return words;
    }

    private static boolean matchesWords(Term term, List<String> words) {
        int size = term.words().size();
        for (int start = 0; start + size <= words.size(); start++) {
            boolean all = true;
            for (int i = 0; i < size && all; i++) {
                var matcher = term.words().get(i).matcher(words.get(start + i));
                all = term.inside() && size == 1 ? matcher.find() : matcher.matches();
            }
            if (all) return true;
        }
        return false;
    }

    /** "nigger" → n{1,}i{1,}g{2,}e{1,}r{1,}, with a plural s or z on the last word of a term. */
    private static Pattern pattern(String word, boolean last) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < word.length(); ) {
            char c = word.charAt(i);
            int run = 1;
            while (i + run < word.length() && word.charAt(i + run) == c) run++;
            regex.append(c).append('{').append(run).append(",}");
            i += run;
        }
        if (last) regex.append("[sz]*");
        return Pattern.compile(regex.toString());
    }

    private static List<Term> load() {
        try (InputStream input = AbusiveLanguage.class.getResourceAsStream("/moderation/abusive-terms.txt")) {
            if (input == null) throw new IllegalStateException("moderation/abusive-terms.txt is missing");
            List<Term> terms = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                boolean inside = line.startsWith("*");
                String[] parts = (inside ? line.substring(1) : line).split(" +");
                List<Pattern> words = new ArrayList<>();
                for (int i = 0; i < parts.length; i++) {
                    if (!parts[i].matches("[a-z]+")) throw new IllegalStateException("Bad abusive term: " + line);
                    words.add(pattern(parts[i], i == parts.length - 1));
                }
                terms.add(new Term(List.copyOf(words), inside));
            }
            return List.copyOf(terms);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
