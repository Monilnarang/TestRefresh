package refactor2refresh;

import org.junit.jupiter.api.Test;
import static org.junit.Assert.assertEquals;

public class Multiplehardcoded_Purified {

    @Test
    void test_1() {
        AddObj a = new AddObj(1);
        AddObj b = new AddObj(2);
        // 24 25
        assertEquals(3, AddObj.add(a, b));
    }

    @Test
    void test_2() {
        AddObj a1 = new AddObj(1);
        AddObj b1 = new AddObj(1);
        // 26 27
        assertEquals(2, AddObj.add(a1, b1));
    }
}
