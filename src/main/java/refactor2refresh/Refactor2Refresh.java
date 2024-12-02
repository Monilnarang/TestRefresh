package refactor2refresh;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Refactor2Refresh {
    // Parameterizer
    protected static final File DEFAULT_OUTPUT_DIR = new File("./z-out-retrofit/");
    private static File outputDir = DEFAULT_OUTPUT_DIR;

    private static String GPT_PROMPT_VALUE_SETS = "For this prompt, only output java code and nothing else, comments in the code is fine. Consider the below value sets of a parameterised unit test and the test itself written in Java. "+
            "                Could you please help me add more value sets to this test method so as to increase the coverage, cover the edge cases, and reveal bugs in the source code." +
            "                Please try to generate minimum number of such value sets. " +
            "                And only output the updated java code." +
            "                Please keep each value set in a separate line.";

    private static String GPT_PROMPT_ATOMIZED_TESTS = "A backward slice is a version of the original program with only the parts that impact a specific variable at a specific point in the program. It removes any code that doesn’t affect that variable, yet it can still run and produce the same result for that variable as the original program.\n" +
            "\n" +
            "Consider the below Java test file. Could you please generate backward slices for every assertion present in the file. And reply with a new test file with atomised tests created from those slices. \n" +
            "\n" +
            "More rules: " +
            "Only reply with Java code and nothing else. " +
            "To name the new atomic methods, use the current names and add _1, _2 .. suffix. " +
            "Don't add any other new code or new text which was not present in the actual file. " +
            "For any code which is commented keep it as it is in the new file." +
            "Make sure to not include the lines which aren't being used in the atomized test." +
            "Here is the test file to do for: ";

    static CompilationUnit configureJavaParserAndGetCompilationUnit(String filePath) throws FileNotFoundException {
        // Configure JavaParser
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver(new ReflectionTypeSolver());

        // Configure JavaParser to use type resolution
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);
        StaticJavaParser.getConfiguration().setAttributeComments(false);
        CompilationUnit cu = StaticJavaParser.parse(new File(filePath));
        return cu;
    }

    static NodeList<Node> getASTStatementsForMethodByName(CompilationUnit cu, String name) {
        NodeList<Statement> statements = null;
        Optional<MethodDeclaration> methodOpt = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(name))
                .findFirst();
        if (methodOpt.isPresent()) {
            MethodDeclaration method = methodOpt.get();
            statements = method.getBody().get().getStatements();
//            System.out.println("Method found:");
//            System.out.println(method);
        } else {
            System.out.println("Method "+ name +" not found in CU.");
        }
        NodeList<Node> statementsNodes = new NodeList<>();
        for (int i=0;i<statements.size();i++) {
            // create copy of statements.get(i) and then add that copy to statementsNodes
            statementsNodes.add(statements.get(i).clone());
        }
        return statementsNodes;
    }

    static boolean areTestsSimilarEnoughToBeRetrofitted(NodeList<Node> statementNodes1, NodeList<Node> statementNodes2) {
        TreeMap<Integer, ArrayList<LiteralStringValueExpr>> paramWiseValueSets = new TreeMap<Integer, ArrayList<LiteralStringValueExpr>>();

        HashMap<String, SlicingUtils.Variable> variableMap1 = new HashMap<String, SlicingUtils.Variable>();
        HashMap<String, SlicingUtils.Variable> variableMap2 = new HashMap<String, SlicingUtils.Variable>();
        HashMap<String, Integer> hardcodedMap1 = new HashMap<String, Integer>();
        HashMap<String, Integer> hardcodedMap2 = new HashMap<String, Integer>();

        ArrayList<TrieLikeNode> trie1 = SlicingUtils.createTrieFromStatementsNew(statementNodes1, hardcodedMap1, paramWiseValueSets);
        ArrayList<TrieLikeNode> trie2 = SlicingUtils.createTrieFromStatementsNew(statementNodes2, hardcodedMap2, paramWiseValueSets);
        SlicingUtils slicingUtils = new SlicingUtils();
        slicingUtils.populateVariableMapFromTrie(trie1, variableMap1);
        slicingUtils.populateVariableMapFromTrie(trie2, variableMap2);
        HashMap<String, String> crossVariableMap = new HashMap<String, String>();
        if(SlicingUtils.compareTrieLists(trie1, trie2, crossVariableMap)) {
//            System.out.println("Tests are similar enough to be retrofitted together");
            return true;
        } else {
//            System.out.println("Tests can't be retrofitted together");
            return false;
        }
    }

    static List<String> extractMethodListFromCU(CompilationUnit cu) {
        // Generate list of all test method name
        List<String> testMethodNames = new ArrayList<>();
        // assuming single test class
        for(int i=0;i<cu.getChildNodes().size();i++) {
            if (cu.getChildNodes().get(i) instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) cu.getChildNodes().get(i);
                for( int j=0;j<clazz.getChildNodes().size();j++) {
                    if (clazz.getChildNodes().get(j) instanceof MethodDeclaration) {
                        MethodDeclaration method = (MethodDeclaration) clazz.getChildNodes().get(j);
                        System.out.println("Test method found: " + method.getNameAsString());
                        testMethodNames.add(method.getNameAsString());
                    }
                }
            }
        }
        return testMethodNames;
    }

    static HashMap<String, NodeList<Node>> extractASTNodesForTestMethods(CompilationUnit cu, List<String> listTestMethods) {
        HashMap<String, NodeList<Node>> statementNodesListMap = new HashMap<>();
        for (String testMethodName : listTestMethods) {
            NodeList<Node> statementNodes = getASTStatementsForMethodByName(cu, testMethodName);
            NodeList<Node> clonedStatementNodes = new NodeList<>();
            for (Node node : statementNodes) {
                clonedStatementNodes.add(node.clone());
            }
            statementNodesListMap.put(testMethodName, clonedStatementNodes);
        }
        return statementNodesListMap;
    }

    static NodeList<Node> generateCloneOfStatements(NodeList<Node> statementNodes) {
        NodeList<Node> clonedStatementNodes = new NodeList<>();
        for (Node node : statementNodes) {
            clonedStatementNodes.add(node.clone());
        }
        return clonedStatementNodes;
    }

    static List<List<UnitTest>> groupSimilarTests(List<String> listTestMethods, HashMap<String, NodeList<Node>> statementNodesListMap) {
        // For every test method, check if it can be retrofitted with any other test method
        // If tests have a similar tree then doing this.
        List<List<UnitTest>> similarTestGroups = new ArrayList<>();
        HashSet<String> testsTaken = new HashSet<>();
        for (String testMethodName1 : listTestMethods) {
            if (testsTaken.contains(testMethodName1)) {
                continue;
            }
            List<UnitTest> toPutTogether = new ArrayList<>();
            NodeList<Node> statementNodes1 = generateCloneOfStatements(statementNodesListMap.get(testMethodName1));
            toPutTogether.add(new UnitTest(testMethodName1, statementNodes1));
            for (String testMethodName2 : listTestMethods) {
                if (!testMethodName1.equals(testMethodName2) && !testsTaken.contains(testMethodName2)) {
                    NodeList<Node> statementNodes2 = generateCloneOfStatements(statementNodesListMap.get(testMethodName2));
                    if (areTestsSimilarEnoughToBeRetrofitted(statementNodes1, statementNodes2)) {
                        testsTaken.add(testMethodName2);
                        toPutTogether.add(new UnitTest(testMethodName2, statementNodes2));
                    }
                }
            }
            testsTaken.add(testMethodName1);
            similarTestGroups.add(toPutTogether);
        }
        return similarTestGroups;
    }

    private static Class<?> inferType(LiteralStringValueExpr expr) {
        if (expr instanceof IntegerLiteralExpr) {
            return int.class;
        } else if (expr instanceof DoubleLiteralExpr) {
            return double.class;
        } else if (expr instanceof LongLiteralExpr) {
            return long.class;
        } else if (expr instanceof CharLiteralExpr) {
            return char.class;
        } else if (expr instanceof StringLiteralExpr) {
            return String.class;
        } else {
            // Fallback for unsupported or custom literal types
            throw new IllegalArgumentException("Unsupported literal type: " + expr.getClass().getSimpleName());
        }
    }
    static List<MethodDeclaration> retrofitSimilarTestsTogether(List<List<UnitTest>> similarTestGroups, CompilationUnit cu) {
        List<MethodDeclaration> newPUTsList = new ArrayList<>();
        for( List<UnitTest> group : similarTestGroups) {
            TreeMap<Integer, ArrayList<LiteralStringValueExpr>> parameter_to_values_map = new TreeMap<Integer, ArrayList<LiteralStringValueExpr>>();
            String firstTestName = group.get(0).Name;
            String newTestName = "parameterisedTest_";
            for( UnitTest unitTest : group) {
                newTestName += unitTest.Name + "_";
                HashMap<String, SlicingUtils.Variable> variableMap = new HashMap<String, SlicingUtils.Variable>();
                HashMap<String, Integer> hardcodedMap = new HashMap<String, Integer>();
                ArrayList<TrieLikeNode> trie = SlicingUtils.createTrieFromStatements(unitTest.Statements, hardcodedMap, parameter_to_values_map);
            }
            // extract method 1 from cu
            Optional<MethodDeclaration> methodOpt = cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(firstTestName))
                    .findFirst();
            if (methodOpt.isPresent()) {
                MethodDeclaration method = methodOpt.get();

                // add parameters : replace hardcoded and add in signature
                for (int i = 0; i < parameter_to_values_map.size(); i++) {
//                    ArrayList<String> values = parameter_to_values_map.get(i + 1);
                    ArrayList<LiteralStringValueExpr> values = parameter_to_values_map.get(i + 1); // TreeMap keys start from 1 in this example
                    if (values != null && !values.isEmpty()) {
//                        String initialValue = values.get(0);
                        LiteralStringValueExpr initialValue = values.get(0);
                        String parameterName = "param" + (i + 1);
                        Class<?> parameterType = inferType(initialValue);
                        method.addParameter(parameterType, parameterName);
//                        method.addParameter(int.class, parameterName);
                        method.findAll(LiteralStringValueExpr.class).forEach(literalExpr -> {
                            if (literalExpr.getValue().equals(initialValue.getValue())) {
                                literalExpr.replace(new NameExpr(parameterName));
                            }
                        });
                    }
                }
                method.getAnnotations().removeIf(annotation -> annotation.getNameAsString().equals("Test"));
                method.addAnnotation(new MarkerAnnotationExpr("ParameterizedTest"));
                method.setName(newTestName);
                NormalAnnotationExpr csvSourceAnnotation = new NormalAnnotationExpr();
//                List<String> csvRows = IntStream.range(0, parameter_to_values_map.get(1).size())
//                        .mapToObj(i -> parameter_to_values_map.values().stream()
//                                .map(list -> list.get(i))
//                                .collect(Collectors.joining(", ")))
//                        .collect(Collectors.toList());

                List<String> csvRows = IntStream.range(0, parameter_to_values_map.get(1).size())
                        .mapToObj(i -> parameter_to_values_map.values().stream()
                                .map(list -> list.get(i).getValue()) // Extract the string value
                                .collect(Collectors.joining(", ")))
                        .collect(Collectors.toList());

                String csvSourceValues = csvRows.stream()
                        .collect(Collectors.joining("\", \"", "\"", "\""));
                csvSourceAnnotation.setName("CsvSource");
                csvSourceAnnotation.addPair("value", "{" + csvSourceValues + "}");
                method.addAnnotation(csvSourceAnnotation);
                newPUTsList.add(method.clone());
            }
        }
        return newPUTsList;
    }

    public static void createGPTEnhancedTestFile(String inputFilePath, List<MethodDeclaration> newMethods) {
        String outputFilePathGPT = inputFilePath.replace(Paths.get(inputFilePath).getFileName().toString(),
                Paths.get(inputFilePath).getFileName().toString().replace(".java", "_GPT.java"));

        // Initialize your GPT model here
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(ApiKeys.OPENAI_API_KEY)
                .modelName("gpt-4o-mini")
                .build();

        try {
            // Parse the input file and set up the output CompilationUnit
            CompilationUnit inputCompilationUnit = StaticJavaParser.parse(new File(inputFilePath));
            CompilationUnit outputCompilationUnit = new CompilationUnit();

            // Copy imports and package declarations
            outputCompilationUnit.getImports().addAll(inputCompilationUnit.getImports());
            outputCompilationUnit.setPackageDeclaration(inputCompilationUnit.getPackageDeclaration().orElse(null));

            // Create the new class in the output unit
            String className = inputCompilationUnit.getType(0).getNameAsString() + "_GPT";
            ClassOrInterfaceDeclaration newClass = outputCompilationUnit.addClass(className);

            // Build a StringBuilder for output content, starting with outputCompilationUnit content
            StringBuilder outputContent = new StringBuilder(outputCompilationUnit.toString());

            // Remove the empty newClass body created by JavaParser and append the opening of the new class manually
            int classStartIndex = outputContent.indexOf("class " + className);
            int classBodyStart = outputContent.indexOf("{", classStartIndex);
            outputContent.setLength(classBodyStart + 1); // Truncate at the class opening brace

            // Generate and append each modified method directly into the new class
            for (MethodDeclaration method : newMethods) {
                String modifiedMethodCode = model.generate(GPT_PROMPT_VALUE_SETS + method);
                modifiedMethodCode = modifiedMethodCode.replace("```java", "").replace("```", "").trim();

                // Append method code inside the class body, preserving comments and formatting
                outputContent.append("\n\n").append(modifiedMethodCode).append("\n");
            }

            // Close the class and complete the file content
            outputContent.append("}\n");

            // Format the content using Google Java Format
            String formattedContent;
            try {
                formattedContent = new Formatter().formatSource(outputContent.toString());
            } catch (FormatterException e) {
                throw new RuntimeException("Error formatting the Java source code", e);
            }

            // Write the formatted content to the output file
            try (BufferedWriter writerGPT = new BufferedWriter(new FileWriter(outputFilePathGPT))) {
                writerGPT.write(formattedContent);
            }

            System.out.println("GPT-enhanced file created successfully at: " + outputFilePathGPT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public static void createGPTEnhancedTestFileOLD(String inputFilePath, List<MethodDeclaration> newMethods) {
        String outputFilePathGPT = inputFilePath.replace(Paths.get(inputFilePath).getFileName().toString(),
                Paths.get(inputFilePath).getFileName().toString().replace(".java", "_GPT.java"));

        // Initialize your GPT model here
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(ApiKeys.OPENAI_API_KEY)
                .modelName("gpt-4o-mini")
                .build();

        try {
            // Parse the input file for imports and class structure
            CompilationUnit inputCompilationUnit = StaticJavaParser.parse(new File(inputFilePath));
            CompilationUnit outputCompilationUnit = new CompilationUnit();

            // Copy import statements to the output CompilationUnit
            outputCompilationUnit.getImports().addAll(inputCompilationUnit.getImports());
            outputCompilationUnit.setPackageDeclaration(inputCompilationUnit.getPackageDeclaration().orElse(null));

            // Create a new class with the same name as original + "_GPT"
            ClassOrInterfaceDeclaration newClass = outputCompilationUnit
                    .addClass(inputCompilationUnit.getType(0).getNameAsString() + "_GPT");

            // Generate and add each new method with GPT enhancements
            for (MethodDeclaration method : newMethods) {
                String modifiedMethodCode = model.generate(GPT_PROMPT_VALUE_SETS + method);
                modifiedMethodCode = modifiedMethodCode.replace("```java", "").replace("```", "").trim();

                // Parse `modifiedMethodCode` as a BodyDeclaration, which preserves comments and formatting
                BodyDeclaration<?> modifiedMethodBody = StaticJavaParser.parseBodyDeclaration(modifiedMethodCode);
                newClass.addMember(modifiedMethodBody);
            }

            // Write the modified CompilationUnit to the output file
            try (BufferedWriter writerGPT = new BufferedWriter(new FileWriter(outputFilePathGPT))) {
                writerGPT.write(outputCompilationUnit.toString());
            }

            System.out.println("GPT-enhanced file created successfully at: " + outputFilePathGPT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String createParameterizedTestFile(String originalFilePath, List<MethodDeclaration> newMethods) throws IOException {
        // Load the original file
        File originalFile = new File(originalFilePath);
        FileInputStream inputStream = new FileInputStream(originalFile);
        CompilationUnit compilationUnit = StaticJavaParser.parse(inputStream);

        // Add these lines right after parsing the original file
        compilationUnit.addImport("org.junit.jupiter.params.ParameterizedTest");
        compilationUnit.addImport("org.junit.jupiter.params.provider.CsvSource");
        // Remove the regular Test import manually
        compilationUnit.getImports().removeIf(importDecl ->
                importDecl.getNameAsString().equals("org.junit.jupiter.api.Test")
        );

        // Find the first class declaration in the file (assuming it's a single class per file)
        ClassOrInterfaceDeclaration originalClass = compilationUnit.getClassByName(originalFile.getName().replace(".java", ""))
                .orElseThrow(() -> new IllegalArgumentException("No class found in the file."));

        // Create a new class name with the suffix "Parameterized"
        String newClassName = originalClass.getNameAsString().replace("Purified", "Parameterized");

        // Clone the original class to preserve all other details (fields, imports, etc.)
        ClassOrInterfaceDeclaration newClass = originalClass.clone();
        newClass.setName(newClassName);  // Rename the class with the "Parameterized" suffix

        // Remove all existing methods in the new class
        newClass.getMethods().forEach(newClass::remove);

        // Add the new methods
        newMethods.forEach(newClass::addMember);

        // Update the class in the CompilationUnit (replace old class with the new one)
        compilationUnit.getTypes().removeIf(td -> td.equals(originalClass));  // Remove the original class
        compilationUnit.addType(newClass);  // Add the new class

        // Define the new file path with "Parameterized" suffix
        String newFileName = originalFile.getName().replace("Purified.java", "Parameterized.java");
        Path newFilePath = Paths.get(originalFile.getParent(), newFileName);

        // Write the updated CompilationUnit to the new file
        try (FileOutputStream outputStream = new FileOutputStream(newFilePath.toFile())) {
            outputStream.write(compilationUnit.toString().getBytes());
        }

        System.out.println("Parameterized test file created at: " + newFilePath.toString());
        return newFilePath.toString();
    }

    public static String createPurifiedTestFile(String inputFilePath) throws IOException {
        // Parse the input Java test file
        CompilationUnit inputCompilationUnit = StaticJavaParser.parse(new File(inputFilePath));

        // Get the original class and create the new class with "_Purified" suffix
        ClassOrInterfaceDeclaration originalClass = inputCompilationUnit.getClassByName(inputCompilationUnit.getType(0).getNameAsString())
                .orElseThrow(() -> new RuntimeException("Class not found in the file"));
        ClassOrInterfaceDeclaration newClass = new ClassOrInterfaceDeclaration();
        newClass.setName(originalClass.getNameAsString() + "_Purified");

        AtomicInteger counter = new AtomicInteger(1);
        // For each test method, generate Purified tests for each assertion
        originalClass.getMethods().stream()
                .filter(method -> method.getAnnotationByName("Test").isPresent())
                .forEach(testMethod -> {
                    // Collect all assert statements for backward slicing
                    List<MethodCallExpr> assertions = testMethod.findAll(MethodCallExpr.class)
                            .stream()
                            .filter(call -> call.getNameAsString().startsWith("assert"))
                            .collect(Collectors.toList());

                    // Generate a separate test method for each assertion
                    assertions.forEach(assertStatement -> {
                        // Clone the original method to create an Purified version
                        MethodDeclaration purifiedMethod = testMethod.clone();
                        String methodName = testMethod.getNameAsString() + "_" + counter.getAndIncrement();
                        purifiedMethod.setName(methodName);

                        // Remove all assertions except the current one
                        purifiedMethod.findAll(MethodCallExpr.class).forEach(call -> {
                            if (call.getNameAsString().startsWith("assert") && !call.equals(assertStatement)) {
                                call.getParentNode().ifPresent(Node::remove);
                            }
                        });

                        // Perform slicing to retain only relevant statements for this assertion
                        performSlicing(purifiedMethod, assertStatement);

                        // Add the purified test method to the new class
                        newClass.addMember(purifiedMethod);
                    });
                });

        // Create the output compilation unit with package and imports
        CompilationUnit outputCompilationUnit = new CompilationUnit();
        outputCompilationUnit.setPackageDeclaration(inputCompilationUnit.getPackageDeclaration().orElse(null));
        outputCompilationUnit.getImports().addAll(inputCompilationUnit.getImports());
        outputCompilationUnit.addType(newClass);

        // Write the new compilation unit to a file with "_Purified" suffix in the name
        String outputFilePath = inputFilePath.replace(".java", "_Purified.java");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath))) {
            writer.write(outputCompilationUnit.toString());
        }

        return outputFilePath;
    }

    private static void performSlicing(MethodDeclaration method, MethodCallExpr assertion) {
        // Collect variables used in the assertion
        Set<String> requiredVariables = getVariablesUsedInAssertion(assertion);

        // Iterate over statements in reverse order to determine which ones to keep
        List<Statement> statements = method.getBody().orElseThrow().getStatements();
        for (int i = statements.size() - 1; i >= 0; i--) {
            Statement stmt = statements.get(i);

            // Check if the statement is an expression that declares or assigns a variable
            if (stmt.isExpressionStmt()) {
                ExpressionStmt exprStmt = stmt.asExpressionStmt();
                Expression expr = exprStmt.getExpression();

                // If the expression is an assignment, check the variable it defines
                if (expr.isAssignExpr()) {
                    AssignExpr assignExpr = expr.asAssignExpr();
                    String varName = assignExpr.getTarget().toString();

                    // Retain the statement if it defines a required variable, and add new dependencies
                    if (requiredVariables.contains(varName)) {
                        requiredVariables.addAll(getVariablesUsedInExpression(assignExpr.getValue()));
                    } else {
                        // Remove the statement if it doesn't define a required variable
                        stmt.remove();
                    }
                } else if (expr.isVariableDeclarationExpr()) {
                    // Handle variable declarations
                    expr.asVariableDeclarationExpr().getVariables().forEach(var -> {
                        String varName = var.getNameAsString();

                        // Retain the statement if it defines a required variable, and add new dependencies
                        if (requiredVariables.contains(varName)) {
                            requiredVariables.addAll(getVariablesUsedInExpression(var.getInitializer().orElse(null)));
                        } else {
                            // Remove the statement if it doesn't define a required variable
                            stmt.remove();
                        }
                    });
                }
            }
        }
    }

    // Helper method to get all variables used in an assertion
    static Set<String> getVariablesUsedInAssertion(MethodCallExpr assertion) {
        Set<String> variables = new HashSet<>();
        assertion.getArguments().forEach(arg -> variables.addAll(getVariablesUsedInExpression(arg)));
        return variables;
    }

    // Helper method to recursively get all variables used in an expression
    private static Set<String> getVariablesUsedInExpression(Expression expression) {
        Set<String> variables = new HashSet<>();
        if (expression == null) return variables;

        expression.walk(NameExpr.class, nameExpr -> variables.add(nameExpr.getNameAsString()));
        return variables;
    }

    public static boolean hasIndependentTests(List<MethodDeclaration> purifiedTestsOfOriginalTest) {
        for (int i = 0; i < purifiedTestsOfOriginalTest.size(); i++) {
            MethodDeclaration test1 = purifiedTestsOfOriginalTest.get(i);
            List<String> test1Lines = extractNonAssertionLines(test1);

            for (int j = i + 1; j < purifiedTestsOfOriginalTest.size(); j++) {
                MethodDeclaration test2 = purifiedTestsOfOriginalTest.get(j);
                List<String> test2Lines = extractNonAssertionLines(test2);

                if (areTestsIndependent(test1Lines, test2Lines)) {
                    System.out.println("Independent tests found: " + test1.getNameAsString() + " and " + test2.getNameAsString());
                    return true; // Found two independent tests
                }
            }
        }
        return false; // No independent tests found
    }

    public static boolean areTestsIndependent(List<String> test1Lines, List<String> test2Lines) {
        // Use a HashSet for efficient lookups
        Set<String> test1Set = new HashSet<>(test1Lines);

        // Check if any line in test2Lines exists in test1Set
        for (String line : test2Lines) {
            if (test1Set.contains(line)) {
                return false; // Common line found, tests are not independent
            }
        }
        return true; // No common line found, tests are independent
    }

    private static List<String> extractNonAssertionLines(MethodDeclaration test) {
        List<String> lines = new ArrayList<>();
        String[] bodyLines = test.getBody().toString().split("\n");

        for (String line : bodyLines) {
            line = line.trim();
            // Exclude assertions and comments
            if (!line.startsWith("assert") && !line.startsWith("//") && !line.isEmpty()) {
                lines.add(line);
            }
        }
        // remove the first and last lines which are { and }
        lines.remove(0);
        lines.remove(lines.size() - 1);
        return lines;
    }

    private static boolean isSubset(List<String> smaller, List<String> larger) {
        Set<String> largerSet = new HashSet<>(larger);
        for (String line : smaller) {
            if (!largerSet.contains(line)) {
                return false;
            }
        }
        return true;
    }

    private static TestFileResult identifyAssertionPastas(String inputFilePath) throws IOException {
        System.out.println("Identifying assertion pastas in file: " + inputFilePath);
        System.out.println("\n Test Method : Status");
        // Parse the input Java test file
        CompilationUnit inputCompilationUnit = StaticJavaParser.parse(new File(inputFilePath));

        // Get the original class and create the new class with "_Purified" suffix
        ClassOrInterfaceDeclaration originalClass = inputCompilationUnit.getClassByName(inputCompilationUnit.getType(0).getNameAsString())
                .orElseThrow(() -> new RuntimeException("Class not found in the file"));
        AtomicInteger totalTests = new AtomicInteger();
        AtomicInteger AssertionPastaCount = new AtomicInteger();

        // For each test method, generate purified tests for each assertion
        originalClass.getMethods().stream()
                .filter(method -> method.getAnnotationByName("Test").isPresent())
                .forEach(testMethod -> {
                    totalTests.getAndIncrement();
                    AtomicInteger counter = new AtomicInteger(1);
                    List<MethodDeclaration> purifiedTestsOfOriginalTest = new ArrayList<>();
                    // Collect all assert statements for backward slicing
                    List<MethodCallExpr> assertions = testMethod.findAll(MethodCallExpr.class)
                            .stream()
                            .filter(call -> call.getNameAsString().startsWith("assert"))
                            .collect(Collectors.toList());

                    // Generate a separate test method for each assertion
                    assertions.forEach(assertStatement -> {
                        // Clone the original method to create an purified version
                        MethodDeclaration purifiedMethod = testMethod.clone();
                        String methodName = testMethod.getNameAsString() + "_" + counter.getAndIncrement();
                        purifiedMethod.setName(methodName);

                        // Remove all assertions except the current one
                        purifiedMethod.findAll(MethodCallExpr.class).forEach(call -> {
                            if (call.getNameAsString().startsWith("assert") && !call.equals(assertStatement)) {
                                call.getParentNode().ifPresent(Node::remove);
                            }
                        });

                        // Perform slicing to retain only relevant statements for this assertion
                        performSlicing(purifiedMethod, assertStatement);
//                        DynamicSlicingAnalyzer.performDynamicSlicing(purifiedMethod, assertStatement);
//                        DynamicSlicer.performDynamicSlicing(purifiedMethod, assertStatement);

                        // Add the purified test method to the list
                        purifiedTestsOfOriginalTest.add(purifiedMethod);
                    });
                    boolean hasIndependentTests = hasIndependentTests(purifiedTestsOfOriginalTest);
                    System.out.println(testMethod.getNameAsString() + " : " + (hasIndependentTests ? "Pasta" : " Inseparable"));
                    if (hasIndependentTests) {
                        AssertionPastaCount.getAndIncrement();
                    }
                });
        TestFileResult result = new TestFileResult(inputFilePath, totalTests.get(), AssertionPastaCount.get(),
                totalTests.get() > 0 ? (AssertionPastaCount.get() * 100.0 / totalTests.get()) : 0.0);
        System.out.println("\n Total tests: " + totalTests.get());
        System.out.println("Assertion Pasta count: " + AssertionPastaCount.get());
        System.out.println("Assertion Pasta Percentage: " + (AssertionPastaCount.get() * 100.0 / totalTests.get()) + "%");
        return result;
    }

    private static boolean isTestFile(String filePath) {
        // Get the file name from the path
        String fileName = Paths.get(filePath).getFileName().toString();
        // Check if it's a Java file and starts with "Test"
        return fileName.endsWith(".java") &&
                fileName.startsWith("Test") &&
                !filePath.contains("/target/") && // Exclude compiled files
                !filePath.contains("/build/");    // Exclude build directories
    }

    private static TestFileResult parseOutput(String output, String filePath) {
        try {
            // Split output into lines
            String[] lines = output.split("\n");

            // Extract metrics from the output
            int totalTests = 0;
            int pastaCount = 0;
            double pastaPercentage = 0.0;

            for (String line : lines) {
                if (line.startsWith("Total tests:")) {
                    totalTests = Integer.parseInt(line.split(":")[1].trim());
                } else if (line.startsWith("Assertion Pasta count:")) {
                    pastaCount = Integer.parseInt(line.split(":")[1].trim());
                } else if (line.startsWith("Assertion Pasta Percentage:")) {
                    pastaPercentage = Double.parseDouble(line.split(":")[1].trim().replace("%", ""));
                }
            }

            return new TestFileResult(filePath, totalTests, pastaCount, pastaPercentage);
        } catch (Exception e) {
            System.err.println("Error parsing output for file: " + filePath);
            return null;
        }
    }

    private static void generateReport(List<TestFileResult> results, String repositoryPath) {
        try {
            String reportPath = Paths.get(repositoryPath, "assertion_pasta_report.md").toString();
            try (PrintWriter writer = new PrintWriter(new FileWriter(reportPath))) {
                // Write report header
                writer.println("# Assertion Pasta Analysis Report");
                writer.println("\nRepository: " + repositoryPath);
                writer.println("\nAnalysis Date: " + new Date());
                writer.println("\n## Summary");

                // Calculate total metrics
                int totalTestFiles = results.size();
                int totalTests = results.stream().mapToInt(r -> r.totalTests).sum();
                int totalPasta = results.stream().mapToInt(r -> r.pastaCount).sum();
                double overallPercentage = totalTests > 0 ?
                        (totalPasta * 100.0 / totalTests) : 0.0;

                writer.println("\n- Total Test Files Analyzed: " + totalTestFiles);
                writer.println("- Total Test Methods: " + totalTests);
                writer.println("- Total Assertion Pasta Cases: " + totalPasta);
                writer.printf("- Overall Assertion Pasta Percentage: %.2f%%\n", overallPercentage);

                // Write detailed results table
                writer.println("\n## Detailed Results\n");
                writer.println("| S No. | Test File | Total Tests | Assertion Pasta Count | Assertion Pasta Percentage |");
                writer.println("|-----|-----------|-------------|---------------------|--------------------------|");
                AtomicInteger count = new AtomicInteger();
                results.stream()
                        .sorted((r1, r2) -> Double.compare(r2.pastaCount, r1.pastaCount))
                        .forEach(result -> {
                            String relativePath = Paths.get(repositoryPath)
                                    .relativize(Paths.get(result.filePath))
                                    .toString();
                            writer.printf("| %d | %s | %d | %d | %.2f%% |\n",
                                    count.incrementAndGet(),
                                    relativePath,
                                    result.totalTests,
                                    result.pastaCount,
                                    result.pastaPercentage);
                        });

                System.out.println("Report generated successfully: " + reportPath);
            }
        } catch (IOException e) {
            System.err.println("Error generating report: " + e.getMessage());
        }
    }

    public static void detectAssertionPastaAndGenerateReport(String pathToJavaRepository) throws IOException {
        // locates and read all the test files in the path folder
        // detects assertion pasta in each test file : use identifyAssertionPastas(inputFile);
        // generates a report file which has a table and results. 4 columns: Test File Name, Total Tests, Assertion Pasta Count, Assertion Pasta Percentage
        List<TestFileResult> results = new ArrayList<>();

        // Find all Java test files in the repository
        Files.walk(Paths.get(pathToJavaRepository))
                .filter(Files::isRegularFile)
                .filter(path -> isTestFile(path.toString()))
                .forEach(path -> {
                    try {
                        // Process the test file
                        // Parse the output to extract metrics
                        TestFileResult result = identifyAssertionPastas(path.toString());
                        if (result != null) {
                            results.add(result);
                        }
                    } catch (Exception e) {
                        System.err.println("Error processing file: " + path);
                        e.printStackTrace();
                    }
                });
        generateReport(results, pathToJavaRepository);
    }
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Please provide the path to the input file as an argument.");
        }
        String inputFile = args[0];
        String operation = args[1];
        if(operation.equals("detect")) {
//             identifyAssertionPastas(inputFile);
            detectAssertionPastaAndGenerateReport(inputFile);
//            TestCoverageGenerator.generateCoverage(inputFile);
        }
        else if (operation.equals("detectin")) {
            identifyAssertionPastas(inputFile);
        }
        else if(operation.equals("fix")) {
            String purifiedTestsFile = createPurifiedTestFile(inputFile);
            System.out.println( "Purified test file created: " + purifiedTestsFile);

            CompilationUnit cu = configureJavaParserAndGetCompilationUnit(purifiedTestsFile);
            // Later Quality check -> [runnable] [same coverage]
            List<String> listTestMethods = extractMethodListFromCU(cu);
            HashMap<String, NodeList<Node>> statementNodesListMap = extractASTNodesForTestMethods(cu, listTestMethods);
            List<List<UnitTest>> similarTestGroups = groupSimilarTests(listTestMethods, statementNodesListMap);
            // ToDo : Impl logic to merge similar tests together
            // create map: similarTestGroups - > lists of params for each group

            List<MethodDeclaration> newPUTs = retrofitSimilarTestsTogether(similarTestGroups, cu);
            String putsFile = createParameterizedTestFile(purifiedTestsFile, newPUTs);
            createGPTEnhancedTestFile(putsFile, newPUTs);
            // ToDo: Add logic to merge separate PUTs into a single PUT
            // ToDo: Experiment on hadoop old dataset test files
            // Later: Quality check -> [runnable] [same or more coverage]
            // Later: [handling failing tests]
        } else {
            throw new IllegalArgumentException("Invalid operation. Please provide either 'detect' or 'fix'.");
        }
    }
}







