package refactor2refresh;

import static org.junit.Assert.assertEquals;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AssertionPasta_Parameterized {

    @ParameterizedTest
    @CsvSource(value = {"5, 6, 11", "16, 15, 31"})
    public void parameterisedTest_test1_1_test1_2_(int param1, int param2, int param3) {
        int a = param1;
        int b = param2;
        int expectedAB = param3;
        assertEquals(expectedAB, a + b);
    }

    @ParameterizedTest
    @CsvSource(value = {"5, 6, 11"})
    public void parameterisedTest_test1_3_(int param1, int param2, int param3) {
        int a = param1;
        int b = param2;
        assertEquals(param3, a + b);
    }
}
