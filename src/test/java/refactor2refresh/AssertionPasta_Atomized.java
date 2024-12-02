package refactor2refresh;

import org.junit.jupiter.api.Test;
import static org.junit.Assert.assertEquals;

class AssertionPasta_Atomized {

    @Test
    public void test1_1() {
        int a = 5;
        int b = 6;
        int expectedAB = 11;
        // 11 12 13
        assertEquals(expectedAB, a + b);
    }

    @Test
    public void test1_2() {
        int c = 16;
        int d = 15;
        int expectedCD = 31;
        // 14 15 16
        assertEquals(expectedCD, c + d);
    }

    @Test
    public void test1_3() {
        int a = 5;
        int b = 6;
        // 11 12
        assertEquals(11, a + b);
    }
}
