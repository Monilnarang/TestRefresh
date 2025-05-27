package refactor2refresh;

import org.junit.jupiter.api.Test;
import static org.junit.Assert.assertEquals;
public class TestAssertionPasta {
    @Test
    public void test1() {
        int a = 11;
        int b = 31;
        int exa = 11;
        int exb = 31;
        assertEquals(exa, a);
        assertEquals(exb, b);
    }
//    @Test
//    public void test2() {
//        int a = 2;
//        int b = 3;
//        int expectedAB = 5;
//        assertEquals(expectedAB, a + b);
//        int c = 1;
//        int d = 2;
//        int expectedCD = 3;
//        assertEquals(expectedCD, c + d);
//        int e = 1;
//        int f = 2;
//        int expectedEF = 3;
//        assertEquals(expectedEF, e + f);
//    }
//    @Test
//    public void test3() {
//        int a = 7;
//        int b = 8;
//        int expectedAB = 15;
//        assertEquals(expectedAB, a + b);
//    }
//    @Test
//    public void test4() {
//        int a = 7;
//        int b = 8;
//        assertEquals(15, a + b);
//    }
}