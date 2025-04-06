package refactor2refresh;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.Assert.assertEquals;

public class AdditionTest {

    @ParameterizedTest
    @CsvSource({
            "1, 2, 3",
            "4, 5, 9",
            "-1, 1, 0",
            "0, 0, 0"
    })
    void testAddition(int a, int b, int expectedSum) {
        assertEquals(expectedSum, add(a, b));
    }

    int add(int x, int y) {
        return x + y;
    }
}
