package refactor2refresh;

import static org.junit.Assert.assertEquals;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class PastaParameterized {

    @ParameterizedTest
    @CsvSource(value = {"5, 6, 11", "16, 15, 31"})
    public void parameterisedTest_test_1_test_2_(int param1, int param2, int param3) {
        int a = param1;
        int b = param2;
        int ab = param3;
        assertEquals(ab, a + b);
    }

    @ParameterizedTest
    @CsvSource(value = {"5, 6, 16, 15, 42"})
    public void parameterisedTest_test_3_(int param1, int param2, int param3, int param4, int param5) {
        int a = param1;
        int b = param2;
        int c = param3;
        int d = param4;
        assertEquals(param5, a + b + c + d);
    }

    @ParameterizedTest
    @CsvSource(value = {"5, 6, 16, 15, 11, 31, 42"})
    public void parameterisedTest_test_4_(int param1, int param2, int param3, int param4, int param5, int param6, int param7) {
        int a = param1;
        int b = param2;
        int c = param3;
        int d = param4;
        int ab = param5;
        int cd = param6;
        assertEquals(param7, ab + cd);
    }

    @ParameterizedTest
    @CsvSource(value = {"5, 6, 16, 15, 11, 42"})
    public void parameterisedTest_test_5_(int param1, int param2, int param3, int param4, int param5, int param6) {
        int a = param1;
        int b = param2;
        int c = param3;
        int d = param4;
        int ab = param5;
        assertEquals(param6, ab + c + d);
    }

    @ParameterizedTest
    @CsvSource(value = {"5, 6, 16, 15, 31, 42"})
    public void parameterisedTest_test_6_(int param1, int param2, int param3, int param4, int param5, int param6) {
        int a = param1;
        int b = param2;
        int c = param3;
        int d = param4;
        int cd = param5;
        assertEquals(param6, a + b + cd);
    }

    @ParameterizedTest
    @CsvSource(value = {"1, 2, 3", "15, 17, 32"})
    void parameterisedTest_test1_1_test1_2_(int param1, int param2, int param3) {
        AddObj a = new AddObj(param1);
        AddObj b = new AddObj(param2);
        assertEquals(param3, AddObj.add(a, b));
    }
}
