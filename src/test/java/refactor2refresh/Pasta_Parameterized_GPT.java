package refactor2refresh;

import static org.junit.Assert.assertEquals;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class Pasta_Parameterized_GPT {

  @ParameterizedTest
  @CsvSource(
      value = {
        "5, 6",
        "16, 15",
        "0, 0", // Edge case: both parameters are zero
        "-1, -1", // Edge case: both parameters are negative
        "Integer.MAX_VALUE, 1", // Edge case: testing upper limit of integer
        "1, Integer.MAX_VALUE", // Edge case: testing upper limit of integer
        "0, 1", // Edge case: one parameter is zero, the other is positive
        "-5, 5" // Edge case: one negative and one positive parameter
      })
  public void parameterisedTest_test_1_test_2_(int param1, int param2) {
    int a = param1;
    int b = param2;
    int ab = a + param2;
    assertEquals(ab, a + b);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "5, 6, 16, 15, 42", // Original test case
        "0, 0, 0, 0, 0", // Edge case: all parameters are zero
        "-1, -1, -1, -1, -4", // Edge case: negative values
        "Integer.MAX_VALUE, 1, 1, 1, Integer.MAX_VALUE + 3", // Edge case: maximum integer value
        "1, 2, 3, 4, 10", // Simple positive case
        "10, 20, -5, -5, 20" // Edge case: mixing positives and negatives
      })
  public void parameterisedTest_test_3_(
      int param1, int param2, int param3, int param4, int param5) {
    int a = param1;
    int b = param2;
    int c = param3;
    int d = param4;
    assertEquals(param5, a + b + c + d);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "5, 6, 15, 16, 42",
        "0, 0, 0, 0, 0", // edge case: all zeros
        "-1, -1, -1, -1, -3", // edge case: negative values
        "Integer.MAX_VALUE, 1, 0, 0, Integer.MAX_VALUE + 1", // edge case: overflow
        "10, 10, 10, 10, 40", // normal case
        "1, 1, 1, 1, 4" // simple case
      })
  public void parameterisedTest_test_4_(
      int param1, int param2, int param3, int param4, int param5) {
    int a = param1;
    int ab = a + param2;
    int d = param3;
    int cd = d + param4;
    assertEquals(param5, ab + cd);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "5, 6, 16, 15, 42", // Original test case
        "0, 0, 0, 0, 0", // Edge case: all parameters are zero
        "-1, -1, -1, -1, -4", // Edge case: negative values
        "Integer.MAX_VALUE, 1, 0, 0, Integer.MAX_VALUE + 1", // Edge case: overflow scenario
        "Integer.MIN_VALUE, -1, -1, -1, Integer.MIN_VALUE - 3" // Edge case: underflow scenario
      })
  public void parameterisedTest_test_5_(
      int param1, int param2, int param3, int param4, int param5) {
    int a = param1;
    int ab = a + param2;
    int c = param3;
    int d = param4;
    assertEquals(param5, ab + c + d);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "5, 6, 15, 16, 42",
        "0, 0, 0, 0, 0", // Testing with zero values
        "-1, -1, -1, -1, -3", // Testing with negative values
        "1, 1, 1, 1, 4", // Testing with small positive values
        "Integer.MAX_VALUE, 1, 1, 1, Integer.MAX_VALUE + 3" // Testing with maximum integer value
      })
  public void parameterisedTest_test_6_(
      int param1, int param2, int param3, int param4, int param5) {
    int a = param1;
    int b = param2;
    int d = param3;
    int cd = d + param4;
    assertEquals(param5, a + b + cd);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "1, 2, 3",
        "15, 17, 32",
        "0, 0, 0", // Edge case: adding zero
        "-1, -1, -2", // Edge case: adding negative numbers
        "Integer.MAX_VALUE, 1, Integer.MIN_VALUE", // Edge case: overflow
        "Integer.MIN_VALUE, -1, Integer.MAX_VALUE", // Edge case: underflow
        "100, 200, 300", // General case
        "-100, 50, -50" // Edge case: negative and positive combination
      })
  void parameterisedTest_test1_7_test1_8_(int param1, int param2, int param3) {
    AddObj a = new AddObj(param1);
    AddObj b = new AddObj(param2);
    assertEquals(param3, AddObj.add(a, b));
  }
}
