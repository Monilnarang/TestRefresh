package refactor2refresh;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertEquals;

public class Multiplehardcoded {
    // Limitation: Unhandled case, redundant hardcoded values
    @Test
    void test() {
        AddObj a = new AddObj(1);
        AddObj b = new AddObj(2);
        AddObj a1 = new AddObj(1);
        AddObj b1 = new AddObj(1);
        assertEquals(3, AddObj.add(a, b)); // 24 25
        assertEquals(2, AddObj.add(a1, b1)); // 26 27
    }
}