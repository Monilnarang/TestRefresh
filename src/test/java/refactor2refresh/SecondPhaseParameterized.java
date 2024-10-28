package refactor2refresh;

import static org.junit.Assert.assertEquals;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class SecondPhaseParameterized {

    @ParameterizedTest
    @CsvSource(value = {"1, 2, 3", "15, 17, 32", "3, 4, 7", "13, 14, 27", "3, 4, 7"})
    void parameterisedTest_test1_test2_test6_test7_test8_(int param1, int param2, int param3) {
        AddObj a = new AddObj(param1);
        AddObj b = new AddObj(param2);
        assertEquals(param3, AddObj.add(a, b));
    }

    @ParameterizedTest
    @CsvSource(value = {"5, 6, 11", "16, 15, 31"})
    public void parameterisedTest_test3_test4_(int param1, int param2, int param3) {
        int a = param1;
        int b = param2;
        int ab = param3;
        assertEquals(ab, a + b);
    }

    @ParameterizedTest
    @CsvSource(value = {"5, 6, 16, 15, 42"})
    public void parameterisedTest_test5_(int param1, int param2, int param3, int param4, int param5) {
        int a = param1;
        int b = param2;
        int c = param3;
        int d = param4;
        assertEquals(param5, a + b + c + d);
    }
}
