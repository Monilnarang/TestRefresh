package refactor2refresh;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertEquals;
public class Pasta {
    @Test
    public void test() {
        int a = 5;
        int b = 6;
        int ab = 11;
        int c = 16;
        int d = 15;
        int cd = 31;
        assertEquals(ab, a + b);
        assertEquals(cd, c + d);
        assertEquals(42, a + b + c + d);
        assertEquals(42, ab + cd);
        assertEquals(42, ab + c + d);
        assertEquals(42, a + b + cd);
    }

    @Test
    void test1() {
        AddObj a = new AddObj(1);
        AddObj b = new AddObj(2);
        AddObj a1 = new AddObj(15);
        AddObj b1 = new AddObj(17);
        assertEquals(3, AddObj.add(a, b));
        assertEquals(32, AddObj.add(a1, b1));
    }
}