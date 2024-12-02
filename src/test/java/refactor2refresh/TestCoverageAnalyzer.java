package refactor2refresh;

import org.jacoco.core.analysis.*;
import org.jacoco.core.data.*;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class TestCoverageAnalyzer {

    public static class CoverageMetrics {
        private final int linesCovered;
        private final int totalLines;
        private final int branchesCovered;
        private final int totalBranches;
        private final Map<String, MethodCoverage> methodCoverages;

        public CoverageMetrics(int linesCovered, int totalLines, int branchesCovered,
                               int totalBranches, Map<String, MethodCoverage> methodCoverages) {
            this.linesCovered = linesCovered;
            this.totalLines = totalLines;
            this.branchesCovered = branchesCovered;
            this.totalBranches = totalBranches;
            this.methodCoverages = methodCoverages;
        }

        public double getLineCoveragePercentage() {
            return totalLines == 0 ? 0 : (linesCovered * 100.0) / totalLines;
        }

        public double getBranchCoveragePercentage() {
            return totalBranches == 0 ? 0 : (branchesCovered * 100.0) / totalBranches;
        }

        @Override
        public String toString() {
            return String.format(
                    "Coverage Metrics:\n" +
                            "Line Coverage: %.2f%% (%d/%d lines)\n" +
                            "Branch Coverage: %.2f%% (%d/%d branches)\n" +
                            "Method Coverage Details:\n%s",
                    getLineCoveragePercentage(), linesCovered, totalLines,
                    getBranchCoveragePercentage(), branchesCovered, totalBranches,
                    getMethodCoverageDetails()
            );
        }

        private String getMethodCoverageDetails() {
            StringBuilder sb = new StringBuilder();
            methodCoverages.forEach((method, coverage) ->
                    sb.append(String.format("  %s: %.2f%% line coverage\n",
                            method, coverage.getCoveragePercentage()))
            );
            return sb.toString();
        }
    }

    public static class MethodCoverage {
        private final int linesCovered;
        private final int totalLines;

        public MethodCoverage(int linesCovered, int totalLines) {
            this.linesCovered = linesCovered;
            this.totalLines = totalLines;
        }

        public double getCoveragePercentage() {
            return totalLines == 0 ? 0 : (linesCovered * 100.0) / totalLines;
        }
    }

    private final String classDirectory;
    private final String targetClassName;
    private final String testClassName;

    public TestCoverageAnalyzer(String classDirectory, String targetClassName, String testClassName) {
        this.classDirectory = classDirectory;
        this.targetClassName = targetClassName;
        this.testClassName = testClassName;
    }

    public CoverageMetrics analyzeCoverage() throws Exception {
        // Initialize JaCoCo runtime
        final IRuntime runtime = new LoggerRuntime();
        final RuntimeData data = new RuntimeData();
        runtime.startup(data);

        try {
            // Read the original class file
            Path classFile = Paths.get(classDirectory, targetClassName.replace('.', '/') + ".class");
            byte[] originalBytes = Files.readAllBytes(classFile);

            // Instrument the class
            final Instrumenter instr = new Instrumenter(runtime);
            byte[] instrumentedBytes = instr.instrument(originalBytes, targetClassName);

            // Create custom class loader and load instrumented class
            Map<String, byte[]> classes = new HashMap<>();
            classes.put(targetClassName, instrumentedBytes);

            // Add test class to the memory class loader
            Path testClassFile = Paths.get("/Users/monilnarang/Documents/Repos/Research/TestRefresh/target/test-classes", testClassName.replace('.', '/') + ".class");
            byte[] testClassBytes = Files.readAllBytes(testClassFile);
            classes.put(testClassName, testClassBytes);

            MemoryClassLoader classLoader = new MemoryClassLoader(classes);

            // Execute the test
            executeTests(classLoader, testClassName);

            // Collect and analyze coverage data
            final ExecutionDataStore executionData = new ExecutionDataStore();
            final SessionInfoStore sessionInfos = new SessionInfoStore();
            data.collect(executionData, sessionInfos, false);

            // Analyze the collected data
            final CoverageBuilder coverageBuilder = new CoverageBuilder();
            final Analyzer analyzer = new Analyzer(executionData, coverageBuilder);

            // Analyze the original class file
            analyzer.analyzeAll(new FileInputStream(classFile.toFile()), targetClassName);

            // Build coverage metrics
            return buildCoverageMetrics(coverageBuilder);

        } finally {
            runtime.shutdown();
        }
    }

    private void executeTests(MemoryClassLoader classLoader, String testClassName) throws Exception {
        Class<?> testClass = classLoader.loadClass(testClassName);

        // Use JUnit's test runner
        org.junit.runner.JUnitCore junit = new org.junit.runner.JUnitCore();
        junit.run(testClass);
    }

    private CoverageMetrics buildCoverageMetrics(CoverageBuilder coverageBuilder) {
        int linesCovered = 0;
        int totalLines = 0;
        int branchesCovered = 0;
        int totalBranches = 0;
        Map<String, MethodCoverage> methodCoverages = new HashMap<>();

        for (IClassCoverage cc : coverageBuilder.getClasses()) {
            linesCovered += cc.getLineCounter().getCoveredCount();
            totalLines += cc.getLineCounter().getTotalCount();
            branchesCovered += cc.getBranchCounter().getCoveredCount();
            totalBranches += cc.getBranchCounter().getTotalCount();

            for (IMethodCoverage mc : cc.getMethods()) {
                methodCoverages.put(
                        mc.getName(),
                        new MethodCoverage(
                                mc.getLineCounter().getCoveredCount(),
                                mc.getLineCounter().getTotalCount()
                        )
                );
            }
        }

        return new CoverageMetrics(
                linesCovered,
                totalLines,
                branchesCovered,
                totalBranches,
                methodCoverages
        );
    }

    private static class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;

        public MemoryClassLoader(Map<String, byte[]> definitions) {
            this.definitions = definitions;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }
    }

    // Example usage
    public static void main(String[] args) {
        try {
            TestCoverageAnalyzer analyzer = new TestCoverageAnalyzer(
                    "/Users/monilnarang/Documents/Repos/Research/TestRefresh/target/classes",              // directory containing compiled classes
                    "refactor2refresh.AddObj",        // fully qualified name of class under test
                    "refactor2refresh.Pasta"     // fully qualified name of test class
            );

            CoverageMetrics metrics = analyzer.analyzeCoverage();
            System.out.println(metrics);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}