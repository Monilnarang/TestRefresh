package refactor2refresh;

import static org.junit.Assert.assertEquals;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class AssertionPasta_Parameterized_GPT {

  @ParameterizedTest
  @CsvSource(
      value = {
        "5, 6, 11",
        "16, 15, 31",
        "0, 0, 0",
        "-1, 1, 0",
        "Integer.MAX_VALUE, 1, Integer.MIN_VALUE",
        "Integer.MIN_VALUE, -1, Integer.MAX_VALUE",
        "-5, -5, -10"
      })
  public void parameterisedTest_test1_1_test1_2_(int param1, int param2, int param3) {
    int a = param1;
    int b = param2;
    int expectedAB = param3;
    assertEquals(expectedAB, a + b);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "5, 6, 11",
        "0, 0, 0", // Edge case: adding two zeros
        "-5, 5, 0", // Edge case: zero sum with negative and positive
        "Integer.MAX_VALUE, 1, Integer.MIN_VALUE", // Edge case: overflow
        "Integer.MIN_VALUE, -1, Integer.MAX_VALUE" // Edge case: underflow
      })
  public void parameterisedTest_test1_3_(int param1, int param2, int param3) {
    int a = param1;
    int b = param2;
    assertEquals(param3, a + b);
  }
}
