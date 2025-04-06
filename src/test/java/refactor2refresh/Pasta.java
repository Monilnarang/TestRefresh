package refactor2refresh;
import org.apache.commons.text.TextStringBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class Pasta {
    @Test
    public void test() {
        int a = 5;
        int b = 6;
        int ab = a + 6;
        int c = 16;
        int d = 15;
        int cd = d + 16;
        assertEquals(ab, a + b); // 8 9 10
        assertEquals(42, a + b + c + d); // 8 9 11 12
        assertEquals(42, ab + cd); // 8 10 12 13
        assertEquals(42, ab + c + d); // 8 10 11 12
        assertEquals(42, a + b + cd); // 8 9 12 13
        assertEquals(cd, c + d); // 11 12 13
    }

    @Test
    void test1() {
        AddObj a = new AddObj(1);
        AddObj b = new AddObj(2);
        AddObj a1 = new AddObj(15);
        AddObj b1 = new AddObj(17);
        assertEquals(3, AddObj.add(a, b)); // 24 25
        assertEquals(32, AddObj.add(a1, b1)); // 26 27
    }



//    @Test
//    public void testHashCode() {
//        final TextStringBuilder sb = new TextStringBuilder(10);
//        final int hc1a = sb.hashCode();
//        final int hc1b = sb.hashCode();
//        Assertions.assertEquals(hc1a, hc1b);
//
//        final int emptyHc = Arrays.hashCode(sb.getChars(new char[0]));
//        assertNotEquals(emptyHc, hc1a);
//
//        final TextStringBuilder sb2 = new TextStringBuilder(8000);
//        final int h1a = sb2.hashCode();
//        final int h1b = sb2.hashCode();
//        Assertions.assertEquals(h1a, h1b);
//
//        sb2.append("abc");
//        final int hc2b2 = sb2.hashCode();
//        final int hc3b2 = sb2.hashCode();
//        Assertions.assertEquals(hc2b2, hc3b2);
//    }

    @Test
    public void testHashCode() {
        final TextStringBuilder sb = new TextStringBuilder(10);
        final int hc1a = sb.hashCode();
        final int hc1b = sb.hashCode();
        Assertions.assertEquals(hc1a, hc1b);

        sb.append("abc");
        final int hc1b1 = sb.hashCode();
        final int hc1b2 = sb.hashCode();
        Assertions.assertEquals(hc1b1, hc1b2);

        final TextStringBuilder sb2 = new TextStringBuilder(2000);
        final int h1a = sb2.hashCode();
        final int h1b = sb2.hashCode();
        Assertions.assertEquals(h1a, h1b);

        sb2.append("123");
        final int hc2b2 = sb2.hashCode();
        final int hc3b2 = sb2.hashCode();
        Assertions.assertEquals(hc2b2, hc3b2);
    }

    @ParameterizedTest
    @CsvSource({
            "10",
            "2000"
    })
    public void testHashCode(int capacity) {
        final TextStringBuilder sb = new TextStringBuilder(capacity);
        final int hcBefore = sb.hashCode();
        final int hcBeforeDuplicate = sb.hashCode();
        Assertions.assertEquals(hcBefore, hcBeforeDuplicate);
    }

    @ParameterizedTest
    @CsvSource({
            "10, 'abc'",
            "2000, '123'"
    })
    public void testHashCodeAppendValue(int capacity, String appendValue) {
        final TextStringBuilder sb = new TextStringBuilder(capacity);

        sb.append(appendValue);
        final int hcAfter = sb.hashCode();
        final int hcAfterDuplicate = sb.hashCode();
        Assertions.assertEquals(hcAfter, hcAfterDuplicate);
    }

    @Test
    public void testHashCodeAndEmptyCondition() {
        final TextStringBuilder sb = new TextStringBuilder(10);
        final int hc1a = sb.hashCode();
        final int hc1b = sb.hashCode();
        Assertions.assertEquals(hc1a, hc1b);

        final int emptyHc = Arrays.hashCode(sb.getChars(new char[0]));
        assertNotEquals(emptyHc, hc1a);
    }

    @Test
    public void testHashCodeWithAppend() {
        final TextStringBuilder sb2 = new TextStringBuilder(8000);
        final int h1a = sb2.hashCode();
        final int h1b = sb2.hashCode();
        Assertions.assertEquals(h1a, h1b);

        sb2.append("abc");
        final int hc2b2 = sb2.hashCode();
        final int hc3b2 = sb2.hashCode();
        Assertions.assertEquals(hc2b2, hc3b2);
    }

    @Test
    public void t1() {
        final TextStringBuilder sb = new TextStringBuilder();
        final int hc1a = sb.hashCode();
        final int hc1b = sb.hashCode();
        Assertions.assertEquals(hc1a, hc1b);
    }

    @Test
    public void t2() {
        final TextStringBuilder sb = new TextStringBuilder();
        final int hc1a = sb.hashCode();
        final int emptyHc = Arrays.hashCode(sb.getChars(new char[0]));
        assertNotEquals(emptyHc, hc1a);
    }

    @Test
    public void t3() {
        final TextStringBuilder sb2 = new TextStringBuilder(100);
        final int hc2 = sb2.hashCode();
        final int hc3 = sb2.hashCode();
        Assertions.assertEquals(hc2, hc3);
    }

    @Test
    public void t4() {
        final TextStringBuilder sb2 = new TextStringBuilder(100);
        sb2.append("abc");;
        final int hc2 = sb2.hashCode();
        final int hc2b2 = sb2.hashCode();
        Assertions.assertEquals(hc2b2, hc2);
    }

    @Test
    public void t5() {
        final TextStringBuilder sb2 = new TextStringBuilder(100);
        sb2.append("abc");
        final int hc2a = sb2.hashCode();
        final int hc2b = sb2.hashCode();
        Assertions.assertEquals(hc2a, hc2b);
    }


}