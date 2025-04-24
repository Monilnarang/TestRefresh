package refactor2refresh;

import java.util.List;

public class TestAnalytics {
    // here line counts are the number of lines in the test method excluding the method signature & provider method
    boolean stopProcessing = false; // if true, means LocP0 != LocP1
    // Before Phase 1
    String testClassName;
    String testMethodName;
    int disjointAssertionsCount;
    int lineCountBefore;
    int assertionCount;

    // After Phase 1: Splitting and Merging
    int lineCountAfterP1;            // aggregate of all test methods
//    int disjointAssertionCount;      // aggregate of all test methods

    // Phase 2: Retrofitting
    boolean isRetrofittingOpportunity;
    boolean retrofittingSuccessful;
    boolean becameRetrofittedTest;

    // After Phase 2: Retrofitting
    int lineCountAfterP2;                 // aggregate of all test methods
    int assertionCountAfterP2;            // aggregate of all test methods
    List<String> testsRefactoredTogether; // only present when becameRetrofittedTest is true

    public TestAnalytics(String testClassName, String testMethodName, int disjointAssertionsCount, int lineCountBefore) {
        this.testClassName = testClassName;
        this.testMethodName = testMethodName;
        this.disjointAssertionsCount = disjointAssertionsCount;
        this.lineCountBefore = lineCountBefore;
    }
}
