package com.p2picks.app.demo;

import java.util.List;
import java.util.Random;

/** The demo users' canned words: chat lines, trash talk and U2Pick questions. Nothing is AI-written. */
final class DemoLines {
    private DemoLines() {}

    record Question(String question, List<String> choices) {}

    static final List<String> CHAT = List.of(
            "who's watching tonight?", "this game is a sweat", "refs are blind lol", "that was a terrible call",
            "I need a bucket", "defense wins championships", "anyone else on the over?", "lock of the night",
            "I'm never betting on this team again", "ok I'm back in", "garbage time is my time", "big if true",
            "let's gooo", "brutal", "called it", "can't watch", "that's a cover", "never in doubt",
            "who proposed this one", "I'll take the other side of that", "bench is cooking", "timeout? really?",
            "pass the snacks", "this line is free money", "I've seen enough", "momentum shift incoming");

    static final List<String> WIN = List.of(
            "too easy", "pay up 😎", "never in doubt", "keep 'em coming", "thanks for the points",
            "I told you", "another one", "cash it");

    static final List<String> LOSS = List.of(
            "rigged", "I'll get it back", "that one hurt", "never again (until tomorrow)", "unreal",
            "fine. FINE.", "should have faded myself", "bad beat");

    static final List<String> REACTIONS = List.of("🔥", "😂", "💀", "👀", "😤", "💯", "🙏", "😬");

    static final List<Question> QUESTIONS = List.of(
            new Question("Who orders food first?", List.of("Me", "Not me")),
            new Question("Overtime tonight?", List.of("Yes", "No")),
            new Question("First timeout before the 6-minute mark?", List.of("Yes", "No")),
            new Question("Does the home crowd boo the refs?", List.of("Yes", "No")),
            new Question("Highest scorer's points?", List.of("Under 25", "25-34", "35+")),
            new Question("Will anyone foul out?", List.of("Yes", "No")),
            new Question("Coach's challenge in the second half?", List.of("Yes", "No")),
            new Question("Last basket: two or three?", List.of("Two", "Three", "Free throw")),
            new Question("Halftime lead holds?", List.of("Yes", "No")),
            new Question("Biggest lead of the night?", List.of("Under 10", "10-19", "20+")),
            new Question("Who wins the turnover battle?", List.of("Home", "Away", "Tied")),
            new Question("Does someone hit a half-court shot?", List.of("Yes", "No")));

    static <T> T any(Random random, List<T> from) {
        return from.get(random.nextInt(from.size()));
    }
}
