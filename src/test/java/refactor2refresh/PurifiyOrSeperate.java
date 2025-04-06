package refactor2refresh;

import org.apache.commons.text.TextStringBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class PurifiyOrSeperate {

    @Test
    public void testHashCode() {
        final TextStringBuilder sb = new TextStringBuilder(100);
        final int hc1a = sb.hashCode();
        final int hc1b = sb.hashCode();
        Assertions.assertEquals(hc1a, hc1b);

        // following TEXT-211 : the hash code of the buffer may not be equals to the hash code of the TextStringBuilder itself
        final int emptyHc = Arrays.hashCode(sb.getChars(new char[0]));
        assertNotEquals(emptyHc, hc1a);

        final TextStringBuilder sb2 = new TextStringBuilder(1000);
        final int h1a = sb2.hashCode();
        final int h1b = sb2.hashCode();
        Assertions.assertEquals(h1a, h1b);

        sb2.append("abc");
        final int hc2b2 = sb2.hashCode();
        final int hc3b2 = sb2.hashCode();
        Assertions.assertEquals(hc2b2, hc3b2);
    }

    @Test
    public void tpurified1() {
        final TextStringBuilder sb = new TextStringBuilder();
        final int hc1a = sb.hashCode();
        final int hc1b = sb.hashCode();
        Assertions.assertEquals(hc1a, hc1b);
    }

    @Test
    public void tpurified2() {
        final TextStringBuilder sb = new TextStringBuilder();
        final int hc1a = sb.hashCode();
        final int emptyHc = Arrays.hashCode(sb.getChars(new char[0]));
        assertNotEquals(emptyHc, hc1a);
    }

    @Test
    public void tpurified3() {
        final TextStringBuilder sb2 = new TextStringBuilder();
        final int hc2 = sb2.hashCode();
        final int hc3 = sb2.hashCode();
        Assertions.assertEquals(hc2, hc3);
    }

    @Test
    public void tpurified4() {
        final TextStringBuilder sb2 = new TextStringBuilder();
        sb2.append("abc");;
        final int hc2 = sb2.hashCode();
        final int hc2b2 = sb2.hashCode();
        Assertions.assertEquals(hc2b2, hc2);
    }

    @Test
    public void tseparated1() {
        final TextStringBuilder sb = new TextStringBuilder();
        final int hc1a = sb.hashCode();
        final int hc1b = sb.hashCode();
        Assertions.assertEquals(hc1a, hc1b);

        // following TEXT-211 : the hash code of the buffer may not be equals to the hash code of the TextStringBuilder itself
        final int emptyHc = Arrays.hashCode(sb.getChars(new char[0]));
        assertNotEquals(emptyHc, hc1a);
    }

    @Test
    public void tseparated2() {
        final TextStringBuilder sb2 = new TextStringBuilder();
        final int h1a = sb2.hashCode();
        final int h1b = sb2.hashCode();
        Assertions.assertEquals(h1a, h1b);

        sb2.append("abc");
        final int hc2b2 = sb2.hashCode();
        final int hc3b2 = sb2.hashCode();
        Assertions.assertEquals(hc2b2, hc3b2);
    }

    // Purification -> 1 PUT + 2 CUTs
    // Separation -> 2 PUTs (loss of retrofitting opportunity) -> but good story
        // -> Assertion pasta is multiple components ->  to fix separate those components -> oh some of them are type 2 clones so lets also retrofit them together




    @Test
    public void testHashCode1() {
        final TextStringBuilder sb = new TextStringBuilder(100);
        final int hc1a = sb.hashCode();
        final int hc1b = sb.hashCode();
        Assertions.assertEquals(hc1a, hc1b);

        final int emptyHc = Arrays.hashCode(sb.getChars(new char[0]));
        assertNotEquals(emptyHc, hc1a);

        final TextStringBuilder sb2 = new TextStringBuilder(1000);
        final int h1a = sb2.hashCode();
        final int h1b = sb2.hashCode();
        Assertions.assertEquals(h1a, h1b);

        final int emptyHc1 = Arrays.hashCode(sb2.getChars(new char[0]));
        assertNotEquals(emptyHc1, h1a);
    }
}
