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
        Assert.assertEquals(ab, a + b);
        Assert.assertEquals(cd, c + d);
        Assert.assertEquals(42, a + b + c + d);
        Assert.assertEquals(42, ab + cd);
        Assert.assertEquals(42, ab + c + d);
        Assert.assertEquals(42, a + b + cd);
    }

    @Test
    void test1() {
        AddObj a = new AddObj(1);
        AddObj b = new AddObj(2);
        AddObj a1 = new AddObj(15);
        AddObj b1 = new AddObj(17);
        Assert.assertEquals(3, AddObj.add(a, b));
        Assert.assertEquals(32, AddObj.add(a1, b1));
    }
}