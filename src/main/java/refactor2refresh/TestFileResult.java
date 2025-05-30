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
    public Map<String, Integer> independentLogicsInTest; // # Separable components of test methods
    List<String> listPastaTests; // # List of test methods which has pasta
    int totalLocInObservedTests;
    Map<String, Integer> filteredTestsMap;

    public TestFileResult(){
        this.filePath = "";
        this.totalTests = 0;
        this.totalConsideredTests = 0;
        this.pastaCount = 0;
        this.pastaPercentage = 0.0;
        this.independentLogicsInTest = new HashMap<>();
        this.listPastaTests = new ArrayList<String>();
        this.totalLocInObservedTests = 0;
        this.filteredTestsMap = new HashMap<>() {{
            put("CTRL", 0);
            put("DS", 0);
            put("LMDA", 0);
            put("PUT", 0);
            put("SLEEP", 0);
            put("MOCK", 0);
            put("OVERRIDE", 0);

        }};
    }

    TestFileResult(String filePath, int totalTests, int totalConsideredTests, int pastaCount, double pastaPercentage) {
        this.filePath = filePath;
        this.totalTests = totalTests;
        this.totalConsideredTests = totalConsideredTests;
        this.pastaCount = pastaCount;
        this.pastaPercentage = pastaPercentage;
        this.independentLogicsInTest = new HashMap<>();
        this.listPastaTests = new ArrayList<String>();
        this.totalLocInObservedTests = 0;
        this.filteredTestsMap = new HashMap<>();
    }
}