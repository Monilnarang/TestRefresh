package refactor2refresh;

import org.junit.jupiter.api.Test;
import static org.junit.Assert.assertEquals;

public class AssertionPasta_Purified {

    @Test
    public void test1_1() {
        int a = 5;
        int b = 6;
        int expectedAB = 11;
        assertEquals(expectedAB, a + b);
    }

    @Test
    public void test1_2() {
        int c = 16;
        int d = 15;
        int expectedCD = 31;
        assertEquals(expectedCD, c + d);
    }

    @Test
    public void test1_3() {
        int a = 5;
        int b = 6;
        assertEquals(11, a + b);
    }
}
