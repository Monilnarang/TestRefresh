package refactor2refresh;

import org.junit.jupiter.api.Test;
import static org.junit.Assert.assertEquals;
public class AssertionPasta {


    @Test
    public void test1() {
        int a = 5;
        int b = 6;
        int expectedAB = 11;
        int c = 16;
        int d = 15;
        int expectedCD = 31;
        assertEquals(expectedAB, a + b);  // 11 12 13
        assertEquals(expectedCD, c + d);  // 14 15 16
        assertEquals(11, a + b); // 11 12
    }

    @Test
    public void test2() {
        int a = 5;
        int b = 6;
        int expectedAB = 11;
        int c = 16;
        int d = 15;
        int expectedCD = 31;
        assertEquals(expectedAB, a + b);  // 11 12 13
        assertEquals(expectedCD, c + d);  // 14 15 16
    }

    @Test
    public void test3() {
        int a = 5;
        int b = 6;
        int expectedAB = 11;
        assertEquals(expectedAB, a + b);  // 11 12 13
        assertEquals(11, a + b); // 11 12
    }
}