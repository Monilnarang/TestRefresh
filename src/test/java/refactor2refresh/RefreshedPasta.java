package refactor2refresh;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class RefreshedPasta {
    @Test
    public void test_1() {
        int a = 5;
        int b = 6;
        int ab = 11;
        assertEquals(ab, a + b);
    }

    @Test
    public void test_2() {
        int c = 16;
        int d = 15;
        int cd = 31;
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
        int b = 6;
        int c = 16;
        int d = 15;
        int ab = 11;
        int cd = 31;
        assertEquals(42, ab + cd);
    }

    @Test
    public void test_5() {
        int a = 5;
        int b = 6;
        int c = 16;
        int d = 15;
        int ab = 11;
        assertEquals(42, ab + c + d);
    }

    @Test
    public void test_6() {
        int a = 5;
        int b = 6;
        int c = 16;
        int d = 15;
        int cd = 31;
        assertEquals(42, a + b + cd);
    }

    @Test
    void test1_1() {
        AddObj a = new AddObj(1);
        AddObj b = new AddObj(2);
        assertEquals(3, AddObj.add(a, b));
    }

    @Test
    void test1_2() {
        AddObj a1 = new AddObj(15);
        AddObj b1 = new AddObj(17);
        assertEquals(32, AddObj.add(a1, b1));
    }
}