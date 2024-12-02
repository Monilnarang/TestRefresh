package refactor2refresh;

import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.core.analysis.*;

import java.io.*;
import java.nio.file.*;

public class TestCoverageGenerator {

    public static void generateCoverage(String testFilePath) {
        try {
            // Compile and run the test file
            Process compileProcess = new ProcessBuilder("javac", testFilePath).start();
            compileProcess.waitFor();
            if (compileProcess.exitValue() != 0) {
                System.err.println("Compilation failed for: " + testFilePath);
                return;
            }

            String testClassName = extractClassName(testFilePath);
            Process runProcess = new ProcessBuilder(
                    "java",
                    "-javaagent:/Users/monilnarang/Documents/Repos/Research/TestRefresh/jacocoagent.jar=destfile=coverage.exec",
                    testClassName
            ).start();
            runProcess.waitFor();
            if (runProcess.exitValue() != 0) {
                System.err.println("Execution failed for: " + testClassName);
                return;
            }

            // Analyze the generated coverage data
            File execFile = new File("coverage.exec");
            ExecFileLoader execFileLoader = new ExecFileLoader();
            execFileLoader.load(execFile);

            CoverageBuilder coverageBuilder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(execFileLoader.getExecutionDataStore(), coverageBuilder);

            File classFile = new File(testClassName + ".class");
            analyzer.analyzeAll(classFile);

            // Print coverage results
            for (IClassCoverage classCoverage : coverageBuilder.getClasses()) {
                System.out.printf("Class: %s\n", classCoverage.getName());
                System.out.printf("Instruction Coverage: %.2f%%\n", classCoverage.getInstructionCounter().getCoveredRatio() * 100);
                System.out.printf("Branch Coverage: %.2f%%\n", classCoverage.getBranchCounter().getCoveredRatio() * 100);
            }

            // Clean up
            execFile.delete();
            classFile.delete();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String extractClassName(String filePath) {
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();
        return fileName.substring(0, fileName.lastIndexOf('.'));
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java TestCoverageGenerator <path-to-test-file>");
            return;
        }

        generateCoverage(args[0]);
    }
}