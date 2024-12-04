package refactor2refresh;

import static org.junit.Assert.assertEquals;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class Multiplehardcoded_Parameterized {

    @ParameterizedTest
    @CsvSource(value = {"1, 2, 3", "2, 1, 3"})
    void parameterisedTest_test_1_test_2_(int param1, int param2, int param3) {
        AddObj a = new AddObj(param1);
        AddObj b = new AddObj(param2);
        assertEquals(param3, AddObj.add(a, b));
    }
}
