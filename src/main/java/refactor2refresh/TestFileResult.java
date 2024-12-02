package refactor2refresh;
public class TestFileResult {
    String filePath;
    int totalTests;
    int pastaCount;
    double pastaPercentage;

    TestFileResult(String filePath, int totalTests, int pastaCount, double pastaPercentage) {
        this.filePath = filePath;
        this.totalTests = totalTests;
        this.pastaCount = pastaCount;
        this.pastaPercentage = pastaPercentage;
    }
}