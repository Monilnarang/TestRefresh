package refactor2refresh;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertEquals;
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
}