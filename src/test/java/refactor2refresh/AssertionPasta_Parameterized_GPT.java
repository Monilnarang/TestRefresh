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
        "0, 0, 0", // Edge case: both parameters are zero
        "-1, -1, -2", // Edge case: both parameters are negative
        "1, -1, 0", // Edge case: one positive and one negative number
        "Integer.MAX_VALUE, 1, Integer.MIN_VALUE", // Edge case: overflow
        "Integer.MIN_VALUE, -1, Integer.MAX_VALUE" // Edge case: underflow
      })
  public void test1_1to2(int param1, int param2, int param3) {
    int a = param1;
    int b = param2;
    int expectedAB = param3;
    assertEquals(expectedAB, a + b);
  }
}
