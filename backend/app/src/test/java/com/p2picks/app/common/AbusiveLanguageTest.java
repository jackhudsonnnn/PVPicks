package com.p2picks.app.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AbusiveLanguageTest {
    @Test
    void catchesSlursThroughCommonDisguises() {
        for (String text : List.of("you fag", "FAGGOT", "faggots lol", "f a g", "f.a.g.g.o.t", "n1gg@", "niggaz",
                "niggggers", "ur a r3tard", "retarded play", "f@gg0t", "fаg" /* Cyrillic а */, "f​ag",
                "sandnigger", "whatafaggotmove", "porch monkey", "pórch mönkey", "gypsy scum", "spics")) {
            assertThat(AbusiveLanguage.contains(text)).as(text).isTrue();
        }
    }

    @Test
    void leavesTrashTalkAndOrdinaryWordsAlone() {
        for (String text : List.of("fuck you, that pick was shit", "Niger and Nigeria", "raccoon", "spice", "spiced",
                "spices", "retardant", "flag", "fagioli", "assassin", "Scunthorpe", "coonhound", "dykes" + "tra",
                "I am a big fan", "a b c d", "go Pakistan", "", "   ")) {
            assertThat(AbusiveLanguage.contains(text)).as(text).isFalse();
        }
        assertThat(AbusiveLanguage.contains(null)).isFalse();
    }

    @Test
    void checksEveryText() {
        assertThat(AbusiveLanguage.containsAny(List.of("Yes", "No"))).isFalse();
        assertThat(AbusiveLanguage.containsAny(List.of("Yes", "f a g"))).isTrue();
    }
}
