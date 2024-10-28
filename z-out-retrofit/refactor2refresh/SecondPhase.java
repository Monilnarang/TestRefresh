/*
	This file was automatically generated as part of a slice with criterion*/
import static org.junit.Assert.assertEquals;

public class Pasta {

    @ParameterizedTest
    @CsvSource(value = {"1, 2, 3", "15, 17, 32"})
    void parameterisedTest(int param1, int param2, int param3) {
        AddObj a = new AddObj(param1);
        AddObj b = new AddObj(param2);
        assertEquals(param3, AddObj.add(a, b));
    }
}
