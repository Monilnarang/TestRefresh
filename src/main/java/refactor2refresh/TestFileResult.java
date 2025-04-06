package refactor2refresh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestFileResult {
    String filePath;
    int totalTests;
    int totalConsideredTests;
    int pastaCount;
    double pastaPercentage;
    public Map<String, Integer> testMethodComponents; // # Separable components of test methods
    List<String> listPastaTests; // # List of test methods which has pasta

    TestFileResult(String filePath, int totalTests, int totalConsideredTests, int pastaCount, double pastaPercentage) {
        this.filePath = filePath;
        this.totalTests = totalTests;
        this.totalConsideredTests = totalConsideredTests;
        this.pastaCount = pastaCount;
        this.pastaPercentage = pastaPercentage;
        this.testMethodComponents = new HashMap<>();
        this.listPastaTests = new ArrayList<String>();
    }
}