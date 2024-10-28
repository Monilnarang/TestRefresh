package refactor2refresh;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertEquals;
public class SecondPhase {
    @Test
    void test1() {
        AddObj a = new AddObj(1);
        AddObj b = new AddObj(2);
        assertEquals(3, AddObj.add(a, b));
    }

    @Test
    void test2() {
        AddObj a = new AddObj(15);
        AddObj b = new AddObj(17);
        assertEquals(32, AddObj.add(a, b));
    }

    @Test
    public void test3() {
        int a = 5;
        int b = 6;
        int ab = 11;
        assertEquals(ab, a + b);
    }

    @Test
    public void test4() {
        int c = 16;
        int d = 15;
        int cd = 31;
        assertEquals(cd, c + d);
    }

    @Test
    public void test5() {
        int a = 5;
        int b = 6;
        int c = 16;
        int d = 15;
        assertEquals(42, a + b + c + d);
    }

    @Test
    public void test6() {
        AddObj a = new AddObj(3);
        AddObj b = new AddObj(4);
        assertEquals(7, AddObj.add(a, b));
    }

    @Test
    public void test7() {
        AddObj a = new AddObj(13);
        AddObj b = new AddObj(14);
        assertEquals(27, AddObj.add(a, b));
    }

    @Test
    public void test8() {
        AddObj a = new AddObj(3);
        AddObj b = new AddObj(4);
        assertEquals(7, AddObj.add(a, b));
    }
}