package refactor2refresh;

import org.junit.jupiter.api.Test;
import static org.junit.Assert.assertEquals;

class Pasta_Atomized {

    @Test
    public void test_1() {
        int a = 5;
        int b = 6;
        int ab = a + 6;
        assertEquals(ab, a + b);
    }

    @Test
    public void test_2() {
        int c = 16;
        int d = 15;
        int cd = d + 16;
        assertEquals(cd, c + d);
    }

    @Test
    public void test_3() {
        int a = 5;
        int b = 6;
        int c = 16;
        int d = 15;
        assertEquals(42, a + b + c + d);
    }

    @Test
    public void test_4() {
        int a = 5;
        int ab = a + 6;
        int d = 15;
        int cd = d + 16;
        assertEquals(42, ab + cd);
    }

    @Test
    public void test_5() {
        int a = 5;
        int ab = a + 6;
        int c = 16;
        int d = 15;
        assertEquals(42, ab + c + d);
    }

    @Test
    public void test_6() {
        int a = 5;
        int b = 6;
        int d = 15;
        int cd = d + 16;
        assertEquals(42, a + b + cd);
    }

    @Test
    void test1_7() {
        AddObj a = new AddObj(1);
        AddObj b = new AddObj(2);
        assertEquals(3, AddObj.add(a, b));
    }

    @Test
    void test1_8() {
        AddObj a1 = new AddObj(15);
        AddObj b1 = new AddObj(17);
        assertEquals(32, AddObj.add(a1, b1));
    }
}
