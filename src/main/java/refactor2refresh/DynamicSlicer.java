package refactor2refresh;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.stream.Collectors;

import static refactor2refresh.Refactor2Refresh.getVariablesUsedInAssertion;

public class DynamicSlicer {

    private static List<TraceEntry> executionTrace = new ArrayList<>();

    public static void performDynamicSlicing(MethodDeclaration method, MethodCallExpr assertion) {
        // Step 1: Instrument the method to collect execution trace
        instrumentMethod(method);

        // Step 2: Execute the instrumented method to capture the trace
        executeInstrumentedMethod(method);

        // Step 3: Analyze the trace to find relevant statements
        Set<String> requiredVariables = getVariablesUsedInAssertion(assertion);
        List<TraceEntry> dynamicSlice = computeDynamicSlice(requiredVariables);

        // Step 4: Update the method to retain only relevant statements
        retainStatementsInMethod(method, dynamicSlice);
    }

    private static void instrumentMethod(MethodDeclaration method) {
        method.getBody().ifPresent(body -> {
            NodeList<Statement> statements = new NodeList<>(body.getStatements()); // Copy to avoid concurrent modification
            NodeList<Statement> newStatements = new NodeList<>();

            for (int i = 0; i < statements.size(); i++) {
                Statement stmt = statements.get(i);

                // Generate valid Java code for logging
                String logStmt = "DynamicSlicer.log(\"stmt-" + i + "\", \"Executed\");";
                Statement logStatement = StaticJavaParser.parseStatement(logStmt);

                // Add log statement before the original statement
                newStatements.add(logStatement);
                newStatements.add(stmt);
            }

            // Replace the original body with the modified one
            body.setStatements(newStatements);
        });
    }

    private static void executeInstrumentedMethod(MethodDeclaration method) {
        // Assuming a runtime environment where the instrumented method can be executed
        try {
            Class<?> clazz = compileAndLoadClass(method);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            clazz.getDeclaredMethod(String.valueOf(method.getName())).invoke(instance);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Class<?> compileAndLoadClass(MethodDeclaration method) throws IOException, ClassNotFoundException {
        // Step 1: Generate the Java source code
        String className = "InstrumentedTestClass";
        String javaCode = generateClassWithMethod(className, method);

        // Step 2: Write the code to a temporary file
        File tempDir = new File(System.getProperty("java.io.tmpdir"));
        File sourceFile = new File(tempDir, className + ".java");
        try (Writer writer = new FileWriter(sourceFile)) {
            writer.write(javaCode);
        }

        // Step 3: Compile the source file
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Java Compiler not available. Ensure JDK is used instead of JRE.");
        }
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjects(sourceFile);

        boolean success = compiler.getTask(null, fileManager, null, null, null, compilationUnits).call();
        if (!success) {
            throw new RuntimeException("Compilation failed. Check the source code.");
        }

        fileManager.close();

        // Step 4: Load the compiled class
        File compiledClassFile = new File(tempDir, className + ".class");
        ClassLoader classLoader = new DynamicClassLoader(tempDir);
        return classLoader.loadClass(className);
    }

    private static String generateClassWithMethod(String className, MethodDeclaration method) {
        // Wrap the method into a simple class for compilation
        return "public class " + className + " {\n" +
                "    public void " + method.getName() + "() {\n" +
                method.getBody().orElseThrow() +
                "    }\n" +
                "}";
    }

    // Custom class loader to load classes from the temporary directory
    private static class DynamicClassLoader extends ClassLoader {
        private final File directory;

        public DynamicClassLoader(File directory) {
            this.directory = directory;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            File classFile = new File(directory, name + ".class");
            if (!classFile.exists()) {
                throw new ClassNotFoundException("Class file not found: " + name);
            }
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(classFile.toPath());
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException("Error reading class file: " + name, e);
            }
        }
    }

    public static void log(String stmtId, Object value) {
        // Log the execution of a statement and its resulting variable values
        executionTrace.add(new TraceEntry(stmtId, value));
    }

    private static List<TraceEntry> computeDynamicSlice(Set<String> requiredVariables) {
        List<TraceEntry> relevantTraceEntries = new ArrayList<>();
        Collections.reverse(executionTrace); // Reverse to process in backward order

        for (TraceEntry entry : executionTrace) {
            if (entry.usesVariables(requiredVariables)) {
                relevantTraceEntries.add(entry);
                requiredVariables.addAll(entry.getDefinedVariables());
            }
        }

        Collections.reverse(relevantTraceEntries); // Restore original order
        return relevantTraceEntries;
    }

    private static void retainStatementsInMethod(MethodDeclaration method, List<TraceEntry> dynamicSlice) {
        method.getBody().ifPresent(body -> {
            List<Statement> statements = body.getStatements();
            Set<String> relevantStmtIds = dynamicSlice.stream()
                    .map(TraceEntry::getStmtId)
                    .collect(Collectors.toSet());

            // Remove irrelevant statements
            statements.removeIf(stmt -> !relevantStmtIds.contains(getStatementId(stmt)));
        });
    }

    private static String getStatementId(Statement stmt) {
        // Extract the statement ID from its logging statement
        return "stmt-" + executionTrace.indexOf(stmt);
    }
}