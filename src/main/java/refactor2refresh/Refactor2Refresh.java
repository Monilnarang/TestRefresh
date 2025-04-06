package refactor2refresh;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.*;

import java.util.concurrent.atomic.AtomicInteger;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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

public class Refactor2Refresh {

    public static int TotalRedundantTests = 0;
    public static int TotalNewPuts = 0;
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
        TreeMap<Integer, ArrayList<LiteralExpr>> paramWiseValueSets = new TreeMap<Integer, ArrayList<LiteralExpr>>();

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

    static List<String> extractTestMethodListFromCU(CompilationUnit cu) {
        List<String> testMethodNames = new ArrayList<>();
        int testCounter = 0;

        for(int i=0;i<cu.getChildNodes().size();i++) {
            if (cu.getChildNodes().get(i) instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration clazz = (ClassOrInterfaceDeclaration) cu.getChildNodes().get(i);

                for( int j=0;j<clazz.getChildNodes().size();j++) {
                    if (clazz.getChildNodes().get(j) instanceof MethodDeclaration) {
                        MethodDeclaration method = (MethodDeclaration) clazz.getChildNodes().get(j);

                        // Check if the method has @Test annotation
                        boolean isTestMethod = method.getAnnotations().stream()
                                .map(AnnotationExpr::getNameAsString)
                                .anyMatch(annotationName -> annotationName.equals("Test"));

                        // Check if the method is marked with @Ignore, @Disabled, or similar annotations
                        boolean isSkipped = method.getAnnotations().stream()
                                .map(AnnotationExpr::getNameAsString)
                                .anyMatch(annotationName -> annotationName.equals("Ignore") || annotationName.equals("Disabled"));

                        if (isTestMethod && !isSkipped) {
    //                      System.out.println("Test method found: " + method.getNameAsString());
                            testCounter++;
                            testMethodNames.add(method.getNameAsString());
                        }
                    }
                }
            }
        }
        System.out.println("Total test methods found: " + testCounter);
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

    private static String generateParameterizedTestName(List<UnitTest> group) {
        if (group == null || group.isEmpty()) {
            throw new IllegalArgumentException("Group cannot be null or empty");
        }

        // Extract the base name from the first test (e.g., "testCamelize" from "testCamelize_1")
        String baseName = group.get(0).Name.replaceAll("_\\d+$", "");

        // Collect numeric suffixes from the test names
        List<Integer> testNumbers = new ArrayList<>();
        for (UnitTest unitTest : group) {
            String testName = unitTest.Name;
            String numberPart = testName.replaceAll("^.*_(\\d+)$", "$1");
            try {
                testNumbers.add(Integer.parseInt(numberPart));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid test name format: " + testName, e);
            }
        }

        // Sort the numbers to identify ranges
        Collections.sort(testNumbers);

        // Identify ranges
        StringBuilder rangeBuilder = new StringBuilder();
        int rangeStart = testNumbers.get(0);
        int previous = rangeStart;

        for (int i = 1; i < testNumbers.size(); i++) {
            int current = testNumbers.get(i);
            if (current != previous + 1) { // End of a range
                if (rangeStart == previous) {
                    rangeBuilder.append(rangeStart).append("_");
                } else {
                    rangeBuilder.append(rangeStart).append("to").append(previous).append("_");
                }
                rangeStart = current; // Start a new range
            }
            previous = current;
        }

        // Handle the last range
        if (rangeStart == previous) {
            rangeBuilder.append(rangeStart);
        } else {
            rangeBuilder.append(rangeStart).append("to").append(previous);
        }

        // Construct and return the new test name
        return baseName + "_" + rangeBuilder.toString();
    }

    private static Class<?> inferType(LiteralExpr expr) {
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
        } else if (expr instanceof BooleanLiteralExpr) {
            return boolean.class;
        }else {
            // Fallback for unsupported or custom literal types
            throw new IllegalArgumentException("Unsupported literal type: " + expr.getClass().getSimpleName());
        }
    }

    public static String getLiteralValue(LiteralExpr literalExpr) {
        if (literalExpr instanceof BooleanLiteralExpr) {
            return String.valueOf(((BooleanLiteralExpr) literalExpr).getValue());
        } else if (literalExpr instanceof StringLiteralExpr) {
            return ((StringLiteralExpr) literalExpr).getValue();
        } else if (literalExpr instanceof IntegerLiteralExpr) {
            return ((IntegerLiteralExpr) literalExpr).getValue();
        } else if (literalExpr instanceof DoubleLiteralExpr) {
            return ((DoubleLiteralExpr) literalExpr).getValue();
        } else if (literalExpr instanceof CharLiteralExpr) {
            return ((CharLiteralExpr) literalExpr).getValue();
        } else if (literalExpr instanceof NullLiteralExpr) {
            return "null";
        } else if (literalExpr instanceof LongLiteralExpr) {
            return ((LongLiteralExpr) literalExpr).getValue();
        } else {
            throw new IllegalArgumentException("Unsupported LiteralExpr type: " + literalExpr.getClass().getSimpleName());
        }
    }

    /**
     * Creates a provider method for the parameterized test
     */
    private static MethodDeclaration createProviderMethod(String methodName, TreeMap<Integer, ArrayList<LiteralExpr>> parameter_to_values_map) {
        // Create the provider method declaration
        MethodDeclaration providerMethod = new MethodDeclaration();
        providerMethod.setName(methodName);
        providerMethod.setType(new ClassOrInterfaceType()
                .setName("Stream")
                .setTypeArguments(new ClassOrInterfaceType().setName("Arguments")));
        providerMethod.setModifiers(Modifier.Keyword.STATIC, Modifier.Keyword.PUBLIC);

        // Create static import statements if necessary
        // These would need to be added to the CompilationUnit separately
        // cu.addImport("org.junit.jupiter.params.provider.Arguments");
        // cu.addImport("java.util.stream.Stream");
        // cu.addImport("static org.junit.jupiter.params.provider.Arguments.arguments");

        // Build the method body
        BlockStmt body = new BlockStmt();

        // Create the return statement with Stream.of(...)
        MethodCallExpr streamOfCall = new MethodCallExpr();
        streamOfCall.setName("of");
        streamOfCall.setScope(new NameExpr("Stream"));

        // Get the parameter count and data size
        int parameterCount = parameter_to_values_map.size();
        int dataSize = parameter_to_values_map.get(1).size();  // Assuming at least one parameter exists

        // For each test case, create an arguments() call
        for (int i = 0; i < dataSize; i++) {
            MethodCallExpr argumentsCall = new MethodCallExpr();
            argumentsCall.setName("arguments");

            // Add all parameters for this test case
            for (int j = 1; j <= parameterCount; j++) {
                ArrayList<LiteralExpr> values = parameter_to_values_map.get(j);
                LiteralExpr value = values.get(i);
                argumentsCall.addArgument(value.clone());  // Clone to avoid modifying original
            }

            streamOfCall.addArgument(argumentsCall);
        }

        // Add the return statement to the method body
        body.addStatement(new ReturnStmt(streamOfCall));
        providerMethod.setBody(body);

        // Add annotations if needed (e.g., @Test would not be appropriate here)
        return providerMethod;
    }

    static List<MethodDeclaration> retrofitSimilarTestsTogether(List<List<UnitTest>> similarTestGroups, CompilationUnit cu) {
        List<MethodDeclaration> newPUTsList = new ArrayList<>();
        for( List<UnitTest> group : similarTestGroups) {
            if(group.size() < 2) {
                continue;
            }
            TreeMap<Integer, ArrayList<LiteralExpr>> parameter_to_values_map = new TreeMap<Integer, ArrayList<LiteralExpr>>();
            String firstTestName = group.get(0).Name;
            String newTestName = generateParameterizedTestName(group); // use this for Assertion Pasta
//            String newTestName = firstTestName + "_Parameterized";
            System.out.println("Group tests: ");
            for( UnitTest unitTest : group) {
                System.out.println("Test Name: " + unitTest.Name);
                HashMap<String, SlicingUtils.Variable> variableMap = new HashMap<String, SlicingUtils.Variable>();
                HashMap<String, Integer> hardcodedMap = new HashMap<String, Integer>();
                ArrayList<TrieLikeNode> trie = SlicingUtils.createTrieFromStatementsNew(unitTest.Statements, hardcodedMap, parameter_to_values_map);
            }
            // extract method 1 from cu
            Optional<MethodDeclaration> methodOpt = cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(firstTestName))
                    .findFirst();
            if (methodOpt.isPresent()) {
                MethodDeclaration method = methodOpt.get();

                // add parameters : replace hardcoded and add in signature
                for (int i = 0; i < parameter_to_values_map.size(); i++) {
                    ArrayList<LiteralExpr> values = parameter_to_values_map.get(i + 1); // TreeMap keys start from 1 in this example
                    if (values != null && !values.isEmpty()) {
                        LiteralExpr initialValue = values.get(0);
                        String parameterName = "param" + (i + 1);
                        Class<?> parameterType = inferType(initialValue);
                        method.addParameter(parameterType, parameterName);
                        method.findAll(LiteralExpr.class).forEach(literalExpr -> {
                            if (getLiteralValue(literalExpr).equals(getLiteralValue(initialValue))) {
                                literalExpr.replace(new NameExpr(parameterName));
                            }
                        });
                    }
                }
                method.getAnnotations().removeIf(annotation -> annotation.getNameAsString().equals("Test"));
                method.addAnnotation(new MarkerAnnotationExpr("ParameterizedTest"));
                method.setName(newTestName);

                // Create the MethodSource annotation
                String methodSourceName = newTestName + "Provider";
                SingleMemberAnnotationExpr methodSourceAnnotation = new SingleMemberAnnotationExpr();
                methodSourceAnnotation.setName("MethodSource");
                methodSourceAnnotation.setMemberValue(new StringLiteralExpr(methodSourceName));
                method.addAnnotation(methodSourceAnnotation);

                // Create the provider method that will supply test parameters
                MethodDeclaration providerMethod = createProviderMethod(methodSourceName, parameter_to_values_map);


                /*
                List<String> csvRows = new ArrayList<>(); // Get the size of the lists in the map (assumes all lists have the same size)
                int size = parameter_to_values_map.get(1).size();
                for (int i = 0; i < size; i++) {
                    StringBuilder row = new StringBuilder();
                    boolean first = true;
                    for (ArrayList<LiteralExpr> list : parameter_to_values_map.values()) {
                        if (!first) {
                            row.append(", ");
                        } else {
                            first = false;
                        }
                        try {
                            row.append(getLiteralValue(list.get(i))); // Extract the string value
                        } catch (Exception e) {
                            System.out.println(e.getCause());
                            System.out.println(e.getMessage());
                            System.out.println("Error extracting value from LiteralStringValueExpr");
//                            throw new RuntimeException("Error extracting value from LiteralStringValueExpr");
                        }

                    }
                    csvRows.add(row.toString());
                }

                NormalAnnotationExpr csvSourceAnnotation = new NormalAnnotationExpr();
                csvSourceAnnotation.setName("CsvSource");

                // Properly indent each value set
                StringBuilder csvBuilder = new StringBuilder();
                csvBuilder.append("{\n");
                for (String row : csvRows) {
                    csvBuilder.append("\t\"").append(row).append("\",\n");
                }
                csvBuilder.setLength(csvBuilder.length() - 2); // Remove the trailing comma and newline
                csvBuilder.append("\n}");

                // Add the formatted values to the annotation
                csvSourceAnnotation.addPair("value", csvBuilder.toString());
                method.addAnnotation(csvSourceAnnotation);
                */
                newPUTsList.add(method.clone());
                newPUTsList.add(providerMethod);
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

    public static String createParameterizedTestFile(String originalFilePath, List<MethodDeclaration> newMethods, List<String> excludedMethods) throws IOException {
        // Load the original file
        File originalFile = new File(originalFilePath);
        FileInputStream inputStream = new FileInputStream(originalFile);
        CompilationUnit compilationUnit = StaticJavaParser.parse(inputStream);

        // Add these lines right after parsing the original file
        compilationUnit.addImport("org.junit.jupiter.params.ParameterizedTest");
        compilationUnit.addImport("org.junit.jupiter.params.provider.MethodSource");
        compilationUnit.addImport("org.junit.jupiter.params.provider.Arguments");
        compilationUnit.addImport("java.util.stream.Stream");
        compilationUnit.addImport("static org.junit.jupiter.params.provider.Arguments.arguments");
//        compilationUnit.addImport("org.junit.jupiter.params.provider.CsvSource");

        // Remove the regular Test import manually -> enable this for Assertion Pasta
//        compilationUnit.getImports().removeIf(importDecl ->
//                importDecl.getNameAsString().equals("org.junit.jupiter.api.Test")
//        );

        // Find the first class declaration in the file (assuming it's a single class per file)
        ClassOrInterfaceDeclaration originalClass = compilationUnit.getClassByName(originalFile.getName().replace(".java", ""))
                .orElseThrow(() -> new IllegalArgumentException("No class found in the file."));

        // Create a new class name with the suffix "Parameterized"
        String newClassName = originalClass.getNameAsString().replace("Purified", "Parameterized"); // use this for Assertion Pasta
//        String newClassName = originalClass.getNameAsString() + "_Parameterized";

        // Clone the original class to preserve all other details (fields, imports, etc.)
        ClassOrInterfaceDeclaration newClass = originalClass.clone();
        newClass.setName(newClassName);  // Rename the class with the "Parameterized" suffix

        // Remove all existing methods in the new class -> use this for Assertion Pasta instead of below
//        newClass.getMethods().forEach(newClass::remove);

        // Remove the excluded methods from the new class
        // todo update for assertion pasta only?
        newClass.getMethods().stream()
                .filter(method -> excludedMethods.contains(method.getNameAsString()))
                .forEach(newClass::remove);

        // Add the new methods
        newMethods.forEach(newClass::addMember);

        // Update the class in the CompilationUnit (replace old class with the new one)
        compilationUnit.getTypes().removeIf(td -> td.equals(originalClass));  // Remove the original class
        compilationUnit.addType(newClass);  // Add the new class

        // Define the new file path with "Parameterized" suffix
        String newFileName = originalFile.getName().replace("Purified.java", "Parameterized.java"); // use this for Assertion Pasta
//        String newFileName = originalFile.getName().replace(".java", "_Parameterized.java");
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
        newClass.setPublic(true);

        AtomicInteger counter = new AtomicInteger(1);

        Map<String, Set<String>> beforeMethodDependencies = extractBeforeMethodDependencies(originalClass);

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
//
//                        // Remove all assertions except the current one
//                        purifiedMethod.findAll(MethodCallExpr.class).forEach(call -> {
//                            if (call.getNameAsString().startsWith("assert") && !call.equals(assertStatement)) {
//                                call.getParentNode().ifPresent(Node::remove);
//                            }
//                        });

                        // New code to remove statements after the current assert statement
                        List<Statement> statements = purifiedMethod.findAll(BlockStmt.class)
                                .get(0).getStatements(); // Assuming the first BlockStmt is the method body

                        int assertIndex = -1;
                        for (int i = 0; i < statements.size(); i++) {
                            if (statements.get(i).findFirst(MethodCallExpr.class)
                                    .filter(call -> call.equals(assertStatement))
                                    .isPresent()) {
                                assertIndex = i;
                                break;
                            }
                        }

                        if (assertIndex != -1) {
                            // Remove all statements after the assert statement
                            for (int i = statements.size() - 1; i > assertIndex; i--) {
                                statements.get(i).remove();
                            }
                        }

                        // Perform slicing to retain only relevant statements for this assertion
                        performSlicing(purifiedMethod, assertStatement, beforeMethodDependencies);

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

    public static Set<String> expandVariablesUsingBeforeDependencies(Set<String> variables, Map<String, Set<String>> beforeMethodDependencies) {
        Set<String> expandedVariables = new HashSet<>(variables);
        for (String var : variables) {
            // If the variable has dependencies in beforeMethodDependencies, add those
            for (Map.Entry<String, Set<String>> dependency : beforeMethodDependencies.entrySet()) {
                // If the current variable is a dependent of any key in beforeMethodDependencies
                if (dependency.getValue().contains(var)) {
                    expandedVariables.add(dependency.getKey());
                }
            }
        }
        return expandedVariables;
    }

    public static boolean checkMethodExpressionsAndRequiredObjects(MethodCallExpr methodCallExpr, Set<String> requiredObjectMethodCalls) {
        List<MethodCallExpr> methodCallExprs = methodCallExpr.findAll(MethodCallExpr.class);
        boolean isRelevant = methodCallExprs.stream()
                .map(call -> call.getScope().map(Object::toString).orElse(""))
                .anyMatch(scopeAsString -> requiredObjectMethodCalls.contains(scopeAsString));
        if (isRelevant || containsRequiredVariable(methodCallExpr, requiredObjectMethodCalls)) {
            return true;
        }
        for(MethodCallExpr call : methodCallExprs) {
            if (call.getScope().isPresent()) {
                if (requiredObjectMethodCalls.contains(call.getScope().get().toString())) {
                    return true;
                }
            }
            if(containsRequiredVariable(call, requiredObjectMethodCalls)) {
                return true;
            }
        }

        return false;
    }

    private static void performSlicing(MethodDeclaration method, MethodCallExpr assertion, Map<String, Set<String>> beforeMethodDependencies) {
        // input method is an atomized test, with only one assertion and all the lines after it removed

        // Collect variables used in the assertion
        Set<String> requiredVariables = getVariablesUsedInAssertion(assertion);

        // Track method calls on required objects
        Set<String> requiredObjectMethodCalls = new HashSet<>();

        // Identify method calls on required objects in the assertion
        findObjectMethodCalls(assertion, requiredVariables, requiredObjectMethodCalls);

        // Expand required variables based on beforeMethodDependencies
        Set<String> expandedRequiredVariables = expandVariablesUsingBeforeDependencies(requiredVariables, beforeMethodDependencies);

        // Iterate over statements in reverse order to determine which ones to keep
        List<Statement> statements = method.getBody().orElseThrow().getStatements();
        for (int i = statements.size() - 1; i >= 0; i--) {
            Statement stmt = statements.get(i);

            // Check if the statement is an expression statement
            if (stmt.isExpressionStmt()) {
                ExpressionStmt exprStmt = stmt.asExpressionStmt();
                Expression expr = exprStmt.getExpression();

                // Check if the statement involves a method call on a required object
                if (expr.isMethodCallExpr()) {
                    MethodCallExpr methodCallExpr = expr.asMethodCallExpr();

                    // If any method call is on a required object or uses a required variable
                    if(methodCallExpr.equals(assertion)) {
                        continue;
                    }
                    else if (checkMethodExpressionsAndRequiredObjects(methodCallExpr, requiredObjectMethodCalls)) {
                        // Add all variables used in the method call and expand dependencies
                        expandedRequiredVariables.addAll(getVariablesUsedInExpression(methodCallExpr));
                        expandedRequiredVariables.addAll(expandVariablesUsingBeforeDependencies(requiredVariables, beforeMethodDependencies));
                    } else {
                        // Remove the statement if not related to required variables
                        stmt.remove();
                        continue;
                    }
                }

                // If the expression is an assignment, check the variable it defines
                if (expr.isAssignExpr()) {
                    AssignExpr assignExpr = expr.asAssignExpr();
                    String varName = assignExpr.getTarget().toString();

                    // Fetch all method call expressions within the assignment expression
                    List<MethodCallExpr> methodCallExprs = assignExpr.findAll(MethodCallExpr.class);

                    boolean anyMethodRelevant = methodCallExprs.stream()
                            .anyMatch(methodCallExpr -> checkMethodExpressionsAndRequiredObjects(methodCallExpr, requiredObjectMethodCalls));

                    // Retain the statement if it defines a required variable, and add new dependencies
                    if (expandedRequiredVariables.contains(varName)) {
                        expandedRequiredVariables.addAll(getVariablesUsedInExpression(assignExpr.getValue()));
                        expandedRequiredVariables.addAll(expandVariablesUsingBeforeDependencies(requiredVariables, beforeMethodDependencies));
                    } else  if (anyMethodRelevant) {
                        // Perform the required actions if any method call is relevant
                        expandedRequiredVariables.addAll(getVariablesUsedInExpression(assignExpr.getValue()));
                        methodCallExprs.forEach(methodCallExpr -> {
                            expandedRequiredVariables.addAll(getVariablesUsedInExpression(methodCallExpr));
                        });
                        expandedRequiredVariables.addAll(expandVariablesUsingBeforeDependencies(requiredVariables, beforeMethodDependencies));
                    }
                    else {
                        // Remove the statement if it doesn't define a required variable
                        stmt.remove();
                    }
                } else if (expr.isVariableDeclarationExpr()) {
                    // Handle variable declarations
                    expr.asVariableDeclarationExpr().getVariables().forEach(var -> {
                        String varName = var.getNameAsString();


                        // Check if the initializer contains any relevant method calls
                        boolean anyMethodRelevant = var.getInitializer()
                                .map(initializer -> initializer.findAll(MethodCallExpr.class).stream()
                                        .anyMatch(methodCallExpr -> checkMethodExpressionsAndRequiredObjects(methodCallExpr, requiredObjectMethodCalls)))
                                .orElse(false);

                        // Retain the statement if it defines a required variable, and add new dependencies
                        if (expandedRequiredVariables.contains(varName) || anyMethodRelevant) {
                            var.getInitializer().ifPresent(initializer -> {
                                expandedRequiredVariables.addAll(getVariablesUsedInExpression(initializer));

                                // Add variables from all method calls within the initializer
                                initializer.findAll(MethodCallExpr.class).forEach(methodCallExpr ->
                                        expandedRequiredVariables.addAll(getVariablesUsedInExpression(methodCallExpr))
                                );
                            });
                            expandedRequiredVariables.addAll(expandVariablesUsingBeforeDependencies(requiredVariables, beforeMethodDependencies));
                        } else {
                            // Remove the statement if it doesn't define a required variable or contain relevant method calls
                            stmt.remove();
                        }
                    });
                }
            }
        }
    }

    // Helper method to find method calls on required objects
    private static void findObjectMethodCalls(
            Expression expr,
            Set<String> requiredVariables,
            Set<String> requiredObjectMethodCalls
    ) {
        // Recursively find method calls on required objects
        if (expr.isMethodCallExpr()) {
            MethodCallExpr methodCallExpr = expr.asMethodCallExpr();

            // Check the scope of the method call
            methodCallExpr.getScope().ifPresent(scope -> {
                String scopeAsString = scope.toString();

                // If the scope is a required variable, add it to tracked method calls
                if (requiredVariables.contains(scopeAsString)) {
                    requiredObjectMethodCalls.add(scopeAsString);
                }
            });

            // Recursively check arguments
            methodCallExpr.getArguments().forEach(arg ->
                    findObjectMethodCalls(arg, requiredVariables, requiredObjectMethodCalls)
            );
        }
        // Recursively check other expression types
        else if (expr.isEnclosedExpr()) {
            findObjectMethodCalls(expr.asEnclosedExpr().getInner(), requiredVariables, requiredObjectMethodCalls);
        }
    }

    // Helper method to check if an expression contains any required variables
    private static boolean containsRequiredVariable(
            Expression expr,
            Set<String> requiredVariables
    ) {
        if (expr == null) return false;

        // Check method call expressions
        if (expr.isMethodCallExpr()) {
            MethodCallExpr methodCallExpr = expr.asMethodCallExpr();

            // Check scope
            if (methodCallExpr.getScope().map(s -> requiredVariables.contains(s.toString())).orElse(false)) {
                return true;
            }

            // Check arguments
            Set<String> variablesInMethodCall = getVariablesUsedInExpression(methodCallExpr);
            return variablesInMethodCall.stream()
                    .anyMatch(requiredVariables::contains);
//            return methodCallExpr.getArguments().stream()
//                    .anyMatch(arg -> containsRequiredVariable(arg, requiredVariables));
        }

        // Check if the expression itself is a required variable
        if (expr.isNameExpr()) {
            return requiredVariables.contains(expr.toString());
        }

        return false;
    }

    private static void performSlicingOld(MethodDeclaration method, MethodCallExpr assertion) {
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
            List<String> test1Assertions = extractAssertionLines(test1);

            for (int j = i + 1; j < purifiedTestsOfOriginalTest.size(); j++) {
                MethodDeclaration test2 = purifiedTestsOfOriginalTest.get(j);
                List<String> test2Lines = extractNonAssertionLines(test2);
                List<String> test2Assertions = extractAssertionLines(test2);

                if (!Collections.disjoint(test1Assertions, test2Assertions)) {
//                    System.out.println("Tests are not independent due to common assertions: " +
//                            test1.getNameAsString() + " and " + test2.getNameAsString());
                    return false;
                }

                if (areTestsIndependent(test1Lines, test2Lines)) {
//                    System.out.println("Independent tests found: " + test1.getNameAsString() + " and " + test2.getNameAsString());
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

    public static List<String> extractAllLines2(MethodDeclaration test) {
        List<String> lines = new ArrayList<>();
        String[] bodyLines = test.getBody().toString().split("\n");
        for (String line : bodyLines) {
            line = line.trim();
            if (!line.startsWith("//") && !line.isEmpty()) {
                lines.add(line);
            }
        }
        // remove the first and last lines which are { and }
        lines.remove(0);
        lines.remove(lines.size() - 1);
        return lines;
    }

    // Helper method to extract assertion lines from a method
    private static List<String> extractAssertionLines(MethodDeclaration method) {
        List<String> assertions = new ArrayList<>();
        method.findAll(MethodCallExpr.class).forEach(call -> {
            if (call.getNameAsString().startsWith("assert")) {
                assertions.add(call.toString()); // Add the assertion line to the list
            }
        });
        return assertions;
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

    private static Map<String, Set<String>> extractBeforeMethodDependencies(ClassOrInterfaceDeclaration testClass) {
        // Map to store variable dependencies
        // Key: Variable name
        // Value: Set of variables that depend on this variable
        Map<String, Set<String>> variableDependencies = new HashMap<>();

        // Find all @Before methods
        List<MethodDeclaration> beforeMethods = testClass.getMethods().stream()
                .filter(method -> method.getAnnotationByName("Before").isPresent())
                .collect(Collectors.toList());

        beforeMethods.forEach(beforeMethod -> {
            // Track variables used and modified in the method
            Map<String, Set<String>> localVariableDependencies = new HashMap<>();

            // Find all variable declarations and assignments
            beforeMethod.findAll(VariableDeclarator.class).forEach(var -> {
                String varName = var.getNameAsString();

                // Find variables used in the initialization
                var.getInitializer().ifPresent(initializer -> {
                    Set<String> usedVariables = findUsedVariables(initializer);

                    // Update dependencies
                    updateDependencyMap(localVariableDependencies, varName, usedVariables);
                });
            });

            // Find method calls and assignments
            beforeMethod.findAll(MethodCallExpr.class).forEach(methodCall -> {
                // Check method calls that modify variables
                methodCall.getScope().ifPresent(scope -> {
                    String scopeStr = scope.toString();

                    // Find variables used in method call arguments
                    Set<String> argumentVariables = methodCall.getArguments().stream()
                            .flatMap(arg -> findUsedVariables(arg).stream())
                            .collect(Collectors.toSet());

                    updateDependencyMap(localVariableDependencies, scopeStr, argumentVariables);
                });
            });

            // Find assignments
            beforeMethod.findAll(AssignExpr.class).forEach(assignExpr -> {
                String targetVar = assignExpr.getTarget().toString();
                Set<String> usedVariables = findUsedVariables(assignExpr.getValue());

                updateDependencyMap(localVariableDependencies, targetVar, usedVariables);
            });

            // Merge local dependencies into global map
            localVariableDependencies.forEach((key, dependencies) ->
                    variableDependencies.merge(key, dependencies, (existing, newDeps) -> {
                        existing.addAll(newDeps);
                        return existing;
                    })
            );
        });

        return variableDependencies;
    }

    private static Set<String> findUsedVariables(Expression expr) {
        Set<String> usedVariables = new HashSet<>();

        // Recursively find variable names in the expression
        if (expr == null) return usedVariables;

        // Check method call expressions
        if (expr.isMethodCallExpr()) {
            MethodCallExpr methodCallExpr = expr.asMethodCallExpr();

            // Add scope variable if exists
            methodCallExpr.getScope().ifPresent(scope -> {
                if (scope.isNameExpr()) {
                    usedVariables.add(scope.toString());
                }
            });

            // Add variables from arguments
            methodCallExpr.getArguments().forEach(arg ->
                    usedVariables.addAll(findUsedVariables(arg))
            );
        }
        // Check name expressions
        else if (expr.isNameExpr()) {
            usedVariables.add(expr.toString());
        }
        // Check for object creation expressions
        else if (expr.isObjectCreationExpr()) {
            ObjectCreationExpr objCreation = expr.asObjectCreationExpr();
            objCreation.getArguments().forEach(arg ->
                    usedVariables.addAll(findUsedVariables(arg))
            );
        }

        return usedVariables;
    }

    // Helper method to update dependency map
    private static void updateDependencyMap(
            Map<String, Set<String>> dependencyMap,
            String targetVariable,
            Set<String> usedVariables
    ) {
        // Initialize the set for target variable if not exists
        dependencyMap.putIfAbsent(targetVariable, new HashSet<>());

        // Add all used variables as dependencies
        dependencyMap.get(targetVariable).addAll(usedVariables);
    }

    public static List<List<MethodDeclaration>> findTestGroups(List<MethodDeclaration> purifiedTestsOfOriginalTest) {
        int n = purifiedTestsOfOriginalTest.size();
        UnionFind uf = new UnionFind(n);

        // Compare each pair of tests
        for (int i = 0; i < n; i++) {
            MethodDeclaration test1 = purifiedTestsOfOriginalTest.get(i);
            List<String> test1Lines = extractNonAssertionLines(test1);

            for (int j = i + 1; j < n; j++) {
                MethodDeclaration test2 = purifiedTestsOfOriginalTest.get(j);
                List<String> test2Lines = extractNonAssertionLines(test2);

                // If tests are not independent, union them
                if (!areTestsIndependent(test1Lines, test2Lines)) {
                    uf.union(i, j);
                }
            }
        }

        // Convert index groups to test groups
        List<List<Integer>> indexGroups = uf.getAllGroups();
        List<List<MethodDeclaration>> testGroups = new ArrayList<>();

        for (List<Integer> group : indexGroups) {
            List<MethodDeclaration> testGroup = group.stream()
                    .map(purifiedTestsOfOriginalTest::get)
                    .collect(Collectors.toList());
            testGroups.add(testGroup);
        }

        return testGroups;
    }

    public static int countSeparableComponents(List<MethodDeclaration> purifiedTestsOfOriginalTest) {
        int n = purifiedTestsOfOriginalTest.size();
        UnionFind uf = new UnionFind(n);

        // Compare each pair of tests
        for (int i = 0; i < n; i++) {
            MethodDeclaration test1 = purifiedTestsOfOriginalTest.get(i);
            List<String> test1Lines = extractNonAssertionLines(test1);

            for (int j = i + 1; j < n; j++) {
                MethodDeclaration test2 = purifiedTestsOfOriginalTest.get(j);
                List<String> test2Lines = extractNonAssertionLines(test2);

                // If tests are not independent, union them
                if (!areTestsIndependent(test1Lines, test2Lines)) {
                    uf.union(i, j);
                }
            }
        }

        // Return the number of disjoint sets
        return uf.getSetCount();
    }

    // Union-Find (Disjoint Set Union) data structure
    private static class UnionFind {
        private int[] parent;
        private int[] rank;
        private int setCount;

        public UnionFind(int size) {
            parent = new int[size];
            rank = new int[size];
            setCount = size; // Initially, each element is its own set

            for (int i = 0; i < size; i++) {
                parent[i] = i; // Each element is its own parent
                rank[i] = 1;   // Initial rank is 1
            }
        }

        // Find the root of the set containing `x`
        public int find(int x) {
            if (parent[x] != x) {
                parent[x] = find(parent[x]); // Path compression
            }
            return parent[x];
        }

        // Union two sets
        public void union(int x, int y) {
            int rootX = find(x);
            int rootY = find(y);

            if (rootX != rootY) {
                // Union by rank
                if (rank[rootX] > rank[rootY]) {
                    parent[rootY] = rootX;
                } else if (rank[rootX] < rank[rootY]) {
                    parent[rootX] = rootY;
                } else {
                    parent[rootY] = rootX;
                    rank[rootX]++;
                }
                setCount--; // Decrease the number of sets
            }
        }

        // Get the number of disjoint sets
        public int getSetCount() {
            return setCount;
        }

        public List<List<Integer>> getAllGroups() {
            Map<Integer, List<Integer>> groups = new HashMap<>();

            // Group elements by their root
            for (int i = 0; i < parent.length; i++) {
                int root = find(i);
                groups.computeIfAbsent(root, k -> new ArrayList<>()).add(i);
            }

            return new ArrayList<>(groups.values());
        }
    }

    private static boolean isWhenMethodInChain(Expression expr) {
        if (!(expr instanceof MethodCallExpr)) {
            return false;
        }

        MethodCallExpr methodCall = (MethodCallExpr) expr;

        // If this is the "when" method, we found it
        if (methodCall.getNameAsString().equals("when")) {
            return true;
        }

        // Otherwise, check parent in the chain if it exists
        if (methodCall.getScope().isPresent()) {
            return isWhenMethodInChain(methodCall.getScope().get());
        }

        return false;
    }

    public static boolean hasComplexControlStructures(MethodDeclaration method) {
        try {

            // Check for Mockito-style mocking with when().thenReturn() pattern
            for (MethodCallExpr methodCall : method.findAll(MethodCallExpr.class)) {
                // Look for method calls that might be part of the chain
                if (methodCall.getNameAsString().equals("thenReturn") ||
                        methodCall.getNameAsString().equals("thenThrow") ||
                        methodCall.getNameAsString().equals("thenAnswer")) {

                    // Check if this is part of a method chain with "when"
                    if (methodCall.getScope().isPresent() &&
                            methodCall.getScope().get() instanceof MethodCallExpr) {

                        MethodCallExpr parentCall = (MethodCallExpr) methodCall.getScope().get();
                        // If the parent or any ancestor in the chain is "when", it's a mocking statement
                        if (isWhenMethodInChain(parentCall)) {
                            return true;
                        }
                    }
                }
            }

            // Check for inner classes or anonymous classes with @Override
            if (!method.findAll(ObjectCreationExpr.class).isEmpty()) {
                for (ObjectCreationExpr objCreation : method.findAll(ObjectCreationExpr.class)) {
                    // Check if it's an anonymous class with body
                    if (objCreation.getAnonymousClassBody().isPresent()) {
                        // Check if any method in the anonymous class has @Override
                        for (BodyDeclaration<?> member : objCreation.getAnonymousClassBody().get()) {
                            if (member.isMethodDeclaration()) {
                                MethodDeclaration innerMethod = (MethodDeclaration) member;
                                if (innerMethod.getAnnotations().stream()
                                        .anyMatch(a -> a.getNameAsString().equals("Override"))) {
                                    return true; // Found @Override in an anonymous class method
                                }
                            }
                        }
                    }
                }
            }

            // Check for try-catch blocks
            if (method.findAll(TryStmt.class).size() > 0) {
                return true;
            }

            // Check for if-else statements
            if (method.findAll(IfStmt.class).size() > 0) {
                return true;
            }

            // Check for for loops
            if (method.findAll(ForStmt.class).size() > 0 ||
                    method.findAll(ForEachStmt.class).size() > 0) {
                return true;
            }

            // Check for while loops
            if (method.findAll(WhileStmt.class).size() > 0 ||
                    method.findAll(DoStmt.class).size() > 0) {
                return true;
            }

            // Check for lambda expressions
            if (method.findAll(LambdaExpr.class).size() > 0) {
                return true;
            }

            // Check for array declarations
            if (!method.findAll(VariableDeclarationExpr.class).isEmpty()) {
                for (VariableDeclarationExpr varDecl : method.findAll(VariableDeclarationExpr.class)) {
                    if (varDecl.getElementType().isArrayType()) {
                        return true; // Explicit array declaration
                    }

                    // Check if the initializer is a method call returning an array
                    for (VariableDeclarator var : varDecl.getVariables()) {
                        if (var.getInitializer().isPresent()) {
                            Expression init = var.getInitializer().get();
                            if (init instanceof MethodCallExpr) {
                                MethodCallExpr methodCall = (MethodCallExpr) init;
                                // A simple heuristic: checking if the name contains 'getBytes' or similar
                                if (methodCall.getNameAsString().equals("getBytes")) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }

            // Check for complex data structures like List, Set, or Map
            List<String> complexTypes = Arrays.asList("List", "Set", "Map");
            for (VariableDeclarationExpr varDecl : method.findAll(VariableDeclarationExpr.class)) {
                String type = varDecl.getElementType().asString();
                for (String complexType : complexTypes) {
                    if (type.contains(complexType)) {
                        return true;
                    }
                }
            }

            // Check for parameterized test annotations
            if (isParameterizedTest(method)) {
                return true;
            }

            // Check for method references (::)
            if (!method.findAll(MethodReferenceExpr.class).isEmpty()) {
                return true;
            }

            return false;
        } catch (Exception e) {
            // If parsing fails, assume the method contains complex structures
            // System.out.println("Error while checking complex control structures: " + e.getMessage());
            return true;
        }
    }

    private static boolean isParameterizedTest(MethodDeclaration method) {
        // Get all annotations on the method
        NodeList<AnnotationExpr> annotations = method.getAnnotations();

        // List of annotations that indicate parameterized tests
        List<String> parameterizedAnnotations = Arrays.asList(
                // JUnit 5 parameterized test annotations
                "ParameterizedTest",
                "ValueSource",
                "CsvSource",
                "MethodSource",
                "EnumSource",
                "ArgumentsSource",
                "CsvFileSource",
                // JUnit 4 parameterized test annotations
                "Parameters",
                "Parameter"
        );

        // Check each annotation
        for (AnnotationExpr annotation : annotations) {
            String annotationName = annotation.getNameAsString();
            if (parameterizedAnnotations.contains(annotationName)) {
                return true;
            }
        }

        // Check method parameters for Parameter annotation (JUnit 4)
        for (Parameter parameter : method.getParameters()) {
            for (AnnotationExpr annotation : parameter.getAnnotations()) {
                String annotationName = annotation.getNameAsString();
                if (parameterizedAnnotations.contains(annotationName)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean hasMockingAnnotations(ClassOrInterfaceDeclaration classDeclaration, CompilationUnit inputCompilationUnit) {
        // List of common mocking annotations and annotations patterns
        List<String> mockingAnnotations = Arrays.asList(
                "Mock", "MockBean", "Spy", "SpyBean", "InjectMocks",
                "MockitoAnnotations", "MockK", "AutoCloseable", "RunWith");

        // Check class annotations (e.g., @RunWith(MockitoJUnitRunner.class))
        for (AnnotationExpr annotation : classDeclaration.getAnnotations()) {
            String annotationName = annotation.getNameAsString();

            // Check for RunWith with Mockito
            if (annotationName.equals("RunWith") && annotation instanceof SingleMemberAnnotationExpr) {
                Expression value = ((SingleMemberAnnotationExpr) annotation).getMemberValue();
                if (value.toString().contains("Mockito")) {
                    return true;
                }
            }

            // Check other mock annotations
            if (mockingAnnotations.contains(annotationName)) {
                return true;
            }
        }

        // Check field annotations for @Mock, @InjectMocks, etc.
        for (FieldDeclaration field : classDeclaration.getFields()) {
            for (AnnotationExpr annotation : field.getAnnotations()) {
                if (mockingAnnotations.contains(annotation.getNameAsString())) {
                    return true;
                }
            }
        }

        // Check for common mock initialization patterns in methods
        for (MethodDeclaration method : classDeclaration.getMethods()) {
            // Check for MockitoAnnotations.initMocks/openMocks calls
            if (method.getName().asString().contains("setUp") ||
                    method.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Before") ||
                            a.getNameAsString().equals("BeforeEach"))) {

                for (MethodCallExpr methodCall : method.findAll(MethodCallExpr.class)) {
                    if (methodCall.getNameAsString().equals("initMocks") ||
                            methodCall.getNameAsString().equals("openMocks")) {
                        return true;
                    }
                }
            }
        }

        // Check import statements for mocking libraries
        boolean hasMockitoImport = inputCompilationUnit.getImports().stream()
                .anyMatch(importDecl ->
                        importDecl.getNameAsString().contains("mockito") ||
                                importDecl.getNameAsString().contains("mock"));

        if (hasMockitoImport) {
            return true;
        }

        return false;
    }

    private static TestFileResult identifyAssertionPastas(String inputFilePath) throws IOException {
        System.out.println("Identifying assertion pastas in file: " + inputFilePath);
        // Parse the input Java test file
        CompilationUnit inputCompilationUnit = StaticJavaParser.parse(new File(inputFilePath));

        // Get the original class and create the new class with "_Purified" suffix
        ClassOrInterfaceDeclaration originalClass = inputCompilationUnit.getClassByName(inputCompilationUnit.getType(0).getNameAsString())
                .orElseThrow(() -> new RuntimeException("Class not found in the file"));
        boolean containsMocking = hasMockingAnnotations(originalClass, inputCompilationUnit);
        if (containsMocking) {
            System.out.println("Skipping file due to mocking annotations: " + inputFilePath);
            return null;
        }

        AtomicInteger totalTests = new AtomicInteger();
        AtomicInteger totalConsideredTests = new AtomicInteger();
        AtomicInteger AssertionPastaCount = new AtomicInteger();

        // Extract @Before method dependencies
        Map<String, Set<String>> beforeMethodDependencies = extractBeforeMethodDependencies(originalClass);

        TestFileResult result = new TestFileResult(inputFilePath, 0, 0, 0,0.0);

        // For each test method, generate purified tests for each assertion
        originalClass.getMethods().stream()
                .filter(method -> method.getAnnotationByName("Test").isPresent())
//                .filter(method -> !hasComplexControlStructures(method)) // ToDo: Count number of tests excluded
                .forEach(testMethod -> {
                    totalTests.getAndIncrement();
                    if(hasComplexControlStructures(testMethod)) {
                        return;
                    }

                    totalConsideredTests.getAndIncrement();
                    if(hasComplexControlStructures(testMethod)) {

                        return; // Skip this test method
                    }

                    AtomicInteger counter = new AtomicInteger(1);
                    List<MethodDeclaration> purifiedTestsOfOriginalTest = new ArrayList<>();
                    // Collect all assert statements for backward slicing
                    List<MethodCallExpr> assertions = testMethod.findAll(MethodCallExpr.class)
                            .stream()
                            .filter(call -> call.getNameAsString().startsWith("assert"))
                            .collect(Collectors.toList());

                    HashMap<String, NodeList<Node>> statementNodesListMap = new HashMap<>();

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

                        // New code to remove statements after the current assert statement
                        List<Statement> statements = purifiedMethod.findAll(BlockStmt.class)
                                .get(0).getStatements(); // Assuming the first BlockStmt is the method body

                        int assertIndex = -1;
                        for (int i = 0; i < statements.size(); i++) {
                            if (statements.get(i).findFirst(MethodCallExpr.class)
                                    .filter(call -> call.equals(assertStatement))
                                    .isPresent()) {
                                assertIndex = i;
                                break;
                            }
                        }

                        if (assertIndex != -1) {
                            // Remove all statements after the assert statement
                            for (int i = statements.size() - 1; i > assertIndex; i--) {
                                statements.get(i).remove();
                            }
                        }

                        performSlicing(purifiedMethod, assertStatement, beforeMethodDependencies);

                        // Add the purified test method to the list
                        purifiedTestsOfOriginalTest.add(purifiedMethod);
                        NodeList<Node> statementsNodes = new NodeList<>();
                        List<Statement> purifiedStatements = purifiedMethod.getBody().get().getStatements();
                        for (int i=0;i<purifiedStatements.size();i++) {
                            // create copy of statements.get(i) and then add that copy to statementsNodes
                            statementsNodes.add(purifiedStatements.get(i).clone());
                        }
                        statementNodesListMap.put(purifiedMethod.getNameAsString(), statementsNodes);
                    });
//                    boolean hasIndependentTests = hasIndependentTests(purifiedTestsOfOriginalTest); -> buggy code misses cases
                    int separableComponents = countSeparableComponents(purifiedTestsOfOriginalTest);
                    if (separableComponents > 1) {
                        System.out.println(testMethod.getNameAsString() + ":");
                        System.out.println(separableComponents + ", ");
                        result.testMethodComponents.put(testMethod.getNameAsString(), separableComponents);
                        try {
                            result.listPastaTests.add(testMethod.getNameAsString());
                        } catch (Exception e) {
                            System.out.println("Error in adding test method to listPastaTests");
                        }

                        AssertionPastaCount.getAndIncrement();
                    }
                });
        result.totalTests = totalTests.get();
        result.totalConsideredTests = totalConsideredTests.get();
        result.pastaCount = AssertionPastaCount.get();
        result.pastaPercentage = totalConsideredTests.get() > 0 ? (AssertionPastaCount.get() * 100.0 / totalConsideredTests.get()) : 0.0;

//        TestFileResult result = new TestFileResult(inputFilePath, totalConsideredTests.get(), AssertionPastaCount.get(),
//                totalConsideredTests.get() > 0 ? (AssertionPastaCount.get() * 100.0 / totalConsideredTests.get()) : 0.0);
        System.out.println("\n Total tests: " + totalTests.get());
        System.out.println("Total Considered tests: " + totalConsideredTests.get());
        System.out.println("Assertion Pasta count: " + AssertionPastaCount.get());
        System.out.println("Assertion Pasta Percentage: " + (AssertionPastaCount.get() * 100.0 / totalConsideredTests.get()) + "%");
        return result;
    }

    private static boolean isTestFile(String filePath) {
        // Get the file name from the path
        String fileName = Paths.get(filePath).getFileName().toString();
        // Check if it's a Java file and starts with "Test"
        return fileName.endsWith(".java") &&
                fileName.contains("Test") &&
                !filePath.contains("/target/") && // Exclude compiled files
                !filePath.contains("/build/");    // Exclude build directories
    }

    private static TestFileResult parseOutput(String output, String filePath) {
        try {
            // Split output into lines
            String[] lines = output.split("\n");

            // Extract metrics from the output
            int totalTests = 0;
            int totalConsideredTests = 0;
            int pastaCount = 0;
            double pastaPercentage = 0.0;

            for (String line : lines) {
                if (line.startsWith("Total tests:")) {
                    totalTests = Integer.parseInt(line.split(":")[1].trim());
                } else if(line.startsWith("Total Considered tests")) {
                    totalConsideredTests = Integer.parseInt(line.split(":")[1].trim());
                } else if (line.startsWith("Assertion Pasta count:")) {
                    pastaCount = Integer.parseInt(line.split(":")[1].trim());
                } else if (line.startsWith("Assertion Pasta Percentage:")) {
                    pastaPercentage = Double.parseDouble(line.split(":")[1].trim().replace("%", ""));
                }
            }

            return new TestFileResult(filePath, totalTests, totalConsideredTests, pastaCount, pastaPercentage);
        } catch (Exception e) {
            System.err.println("Error parsing output for file: " + filePath);
            return null;
        }
    }

    public static String getLastFolderName(String repositoryPath) {
        Path path = Paths.get(repositoryPath);
        return path.getFileName().toString(); // Extracts the last folder name
    }

    private static void generateReportAssertionPasta(List<TestFileResult> results, String repositoryPath) {
        try {
            String reportPath = Paths.get("/Users/monilnarang/Documents/Research Evaluations/Apr5", getLastFolderName(repositoryPath) + ".md").toString();
            try (PrintWriter writer = new PrintWriter(new FileWriter(reportPath))) {
                // Write report header
                writer.println("# Assertion Pasta Analysis Report");
                writer.println("\nRepository: " + repositoryPath);
                writer.println("\nAnalysis Date: " + new Date());
                writer.println("\n## Summary");

                // Calculate total metrics
                int totalTestFiles = results.size();
                int totalTests = results.stream().mapToInt(r -> r.totalTests).sum();
                int totalConsideredTests = results.stream().mapToInt(r -> r.totalConsideredTests).sum();
                int totalPasta = results.stream().mapToInt(r -> r.pastaCount).sum();
                AtomicInteger totalIndependentLogics = new AtomicInteger();
                double overallPercentage = totalConsideredTests > 0 ?
                        (totalPasta * 100.0 / totalConsideredTests) : 0.0;

                Map<Integer, Integer> separableComponentFrequency = new HashMap<>();
                for (TestFileResult result : results) {
                    for (int components : result.testMethodComponents.values()) {
                        separableComponentFrequency.put(components, separableComponentFrequency.getOrDefault(components, 0) + 1);
                    }
                }

                writer.println("\n- Total Test Files Analyzed: " + totalTestFiles);
                writer.println("- Total Test Methods: " + totalTests);
                writer.println("- Total Considered Test Methods: " + totalConsideredTests);
                writer.println("- Total Assertion Pasta Cases: " + totalPasta);
                writer.printf("- Overall Assertion Pasta Percentage: %.2f%%\n", overallPercentage);


                // Write separable component frequency map
                writer.println("\n### Separable Component Frequency");
                writer.println("| Number of Separable Components | Frequency |");
                writer.println("|--------------------------------|-----------|");
                separableComponentFrequency.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> {
                            writer.printf("| %-30d | %-9d |\n", entry.getKey(), entry.getValue());
                            totalIndependentLogics.addAndGet(entry.getValue() * entry.getKey());
                        });

                writer.println("- Total Independent Logics: " + totalIndependentLogics.get());

                // Write detailed results table
                writer.println("\n## Detailed Results\n");
                writer.println("| S No. | Test File | Total Tests | Assertion Pasta Count | Assertion Pasta Percentage | Test Methods (Separable Components) |");
                writer.println("|-----|-----------|-------------|---------------------|--------------------------|-------------------------------------|");
                AtomicInteger count = new AtomicInteger();
                results.stream()
                        .sorted((r1, r2) -> Double.compare(r2.pastaCount, r1.pastaCount))
                        .forEach(result -> {
                            String relativePath = Paths.get(repositoryPath)
                                    .relativize(Paths.get(result.filePath))
                                    .toString();
                            // Format test method details
                            StringBuilder testMethodDetails = new StringBuilder();
                            result.testMethodComponents.forEach((methodName, components) -> {
                                testMethodDetails.append(methodName).append(" (").append(components).append("), ");
                            });

                            // Remove the trailing comma and space if there are any test methods
                            if (testMethodDetails.length() > 0) {
                                testMethodDetails.setLength(testMethodDetails.length() - 2);
                            } else {
                                testMethodDetails.append("N/A");
                            }

                            writer.printf("| %d | %s | %d | %d | %.2f%% | %s |\n",
                                    count.incrementAndGet(),
                                    relativePath,
                                    result.totalConsideredTests,
                                    result.pastaCount,
                                    result.pastaPercentage,
                                    testMethodDetails.toString());
                        });

                System.out.println("Report generated successfully: " + reportPath);
            }
        } catch (IOException e) {
            System.err.println("Error generating report: " + e.getMessage());
        }
    }

    public static List<TestFileResult> getAssertionPastaResultsInRepo(String pathToJavaRepository) throws IOException {
        List<TestFileResult> results = new ArrayList<>();

        // Find all Java test files in the repository
        Files.walk(Paths.get(pathToJavaRepository))
                .filter(Files::isRegularFile)
                .filter(path -> isTestFile(path.toString()))
                .filter(path -> !path.toString().matches(".*(_Purified|_Parameterized|_Parameterized_GPT)\\.java$"))
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
        return results;
    }

    public static ClassOrInterfaceDeclaration createNewClassWithoutTests(ClassOrInterfaceDeclaration originalClass) {
        ClassOrInterfaceDeclaration newClass = originalClass.clone();
        newClass.setName(originalClass.getNameAsString() + "_Purified");
        // remove all test methods from the new class

        // Get all methods in the class
        NodeList<BodyDeclaration<?>> members = newClass.getMembers();
        List<MethodDeclaration> methodsToRemove = new ArrayList<>();

        // Identify test methods to remove
        for (BodyDeclaration<?> member : members) {
            if (member.isMethodDeclaration()) {
                MethodDeclaration method = (MethodDeclaration) member;

                // Check for any test-related annotations
                if (method.getAnnotations().stream().anyMatch(annotation -> {
                    String name = annotation.getNameAsString();
                    return name.equals("Test") ||
                            name.equals("ParameterizedTest") ||
                            name.equals("ValueSource") ||
                            name.equals("CsvSource") ||
                            name.equals("MethodSource") ||
                            name.equals("EnumSource") ||
                            name.equals("ArgumentsSource") ||
                            name.equals("CsvFileSource") ||
                            name.equals("Parameters") ||
                            name.equals("Parameter") ||
                            name.equals("Theory");
                })) {
                    methodsToRemove.add(method);
                }
                // Also check for methods that start with "test"
                else if (method.getNameAsString().toLowerCase().startsWith("test")) {
                    methodsToRemove.add(method);
                }
            }
        }

        // Remove the identified test methods
        methodsToRemove.forEach(method -> method.remove());
        return newClass;
    }

    public static String createJavaClassFromClassDeclarationObject(ClassOrInterfaceDeclaration newClass, String filePath, CompilationUnit inputCompilationUnit) {
        CompilationUnit outputCompilationUnit = new CompilationUnit();
        outputCompilationUnit.setPackageDeclaration(inputCompilationUnit.getPackageDeclaration().orElse(null));
        outputCompilationUnit.getImports().addAll(inputCompilationUnit.getImports());
        outputCompilationUnit.addType(newClass);
        String purifiedOutputFilePath = filePath.replace(".java", "_Purified.java");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(purifiedOutputFilePath))) {
            writer.write(outputCompilationUnit.toString());
        } catch (IOException e) {
            System.err.println("Error writing output to file: " + purifiedOutputFilePath);
        }
        return purifiedOutputFilePath;
    }

    static class ResultCreateNewClassFileWithSplittedTests {
        String newClassFilePath;
        int newSeparatedTests;
        public ResultCreateNewClassFileWithSplittedTests(String newClassFilePath, int newSeparatedTests) {
            this.newClassFilePath = newClassFilePath;
            this.newSeparatedTests = newSeparatedTests;
        }
    }

    public static ResultCreateNewClassFileWithSplittedTests createNewClassFileWithSplittedTests(TestFileResult result) throws IOException {
        CompilationUnit inputCompilationUnit = StaticJavaParser.parse(new File(result.filePath));
        ClassOrInterfaceDeclaration originalClass = inputCompilationUnit.getClassByName(inputCompilationUnit.getType(0).getNameAsString())
                .orElseThrow(() -> new RuntimeException("Class not found in the file"));

        ClassOrInterfaceDeclaration newClass = createNewClassWithoutTests(originalClass);
        ResultSeparateIndependentAssertionClustersAndAddToClass resultY = separateIndependentAssertionClustersAndAddToClass(originalClass, newClass, result);
        String newClassFilePath = createJavaClassFromClassDeclarationObject(resultY.newClass, result.filePath, inputCompilationUnit);
        return new ResultCreateNewClassFileWithSplittedTests(newClassFilePath, resultY.newSeparatedTests);

    }

    static class ResultSeparateIndependentAssertionClustersAndAddToClass {
        ClassOrInterfaceDeclaration newClass;
        int newSeparatedTests;

        public ResultSeparateIndependentAssertionClustersAndAddToClass(ClassOrInterfaceDeclaration newClass, int newSeparatedTests) {
            this.newClass = newClass;
            this.newSeparatedTests = newSeparatedTests;
        }
    }

    public static ResultSeparateIndependentAssertionClustersAndAddToClass separateIndependentAssertionClustersAndAddToClass(ClassOrInterfaceDeclaration originalClass, ClassOrInterfaceDeclaration newClass, TestFileResult result) {
        // separate independent assertion clusters from the original class and add to new class
        AtomicInteger newSeparatedTests = new AtomicInteger();
        Map<String, Set<String>> beforeMethodDependencies = extractBeforeMethodDependencies(originalClass);

        originalClass.getMethods().stream()
                .filter(method -> method.getAnnotationByName("Test").isPresent())
                .filter(method -> result.listPastaTests.contains(method.getNameAsString()))
                .forEach(testMethod -> {
                    AtomicInteger counter = new AtomicInteger(1);
                    List<MethodDeclaration> purifiedTestsOfOriginalTest = new ArrayList<>();
                    // Collect all assert statements for backward slicing
                    List<MethodCallExpr> assertions = testMethod.findAll(MethodCallExpr.class)
                            .stream()
                            .filter(call -> call.getNameAsString().startsWith("assert"))
                            .collect(Collectors.toList());

                    HashMap<String, NodeList<Node>> statementNodesListMap = new HashMap<>();

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

                        // New code to remove statements after the current assert statement
                        List<Statement> statements = purifiedMethod.findAll(BlockStmt.class)
                                .get(0).getStatements(); // Assuming the first BlockStmt is the method body

                        int assertIndex = -1;
                        for (int i = 0; i < statements.size(); i++) {
                            if (statements.get(i).findFirst(MethodCallExpr.class)
                                    .filter(call -> call.equals(assertStatement))
                                    .isPresent()) {
                                assertIndex = i;
                                break;
                            }
                        }

                        if (assertIndex != -1) {
                            // Remove all statements after the assert statement
                            for (int i = statements.size() - 1; i > assertIndex; i--) {
                                statements.get(i).remove();
                            }
                        }

                        performSlicing(purifiedMethod, assertStatement, beforeMethodDependencies);
                        purifiedTestsOfOriginalTest.add(purifiedMethod);

                        // Add the purified-> Separated test method to the new class
//                        newClass.addMember(purifiedMethod);
                    });
                    List<MethodDeclaration> clusteredTests = clusterDependentPurifiedTests(purifiedTestsOfOriginalTest, testMethod);
                    newSeparatedTests.addAndGet(clusteredTests.size());
                    clusteredTests.forEach(newClass::addMember);
                });
        return new ResultSeparateIndependentAssertionClustersAndAddToClass(newClass, newSeparatedTests.get());
    }

    public static List<MethodDeclaration> clusterDependentPurifiedTests(List<MethodDeclaration> purifiedTestsOfOriginalTest, MethodDeclaration originalTest) {
        List<List<MethodDeclaration>> dependentPurifiedTestGroups = findTestGroups(purifiedTestsOfOriginalTest);
        if(dependentPurifiedTestGroups.size() <= 1) {
            throw new RuntimeException("Error in separating independent assertion clusters, expected separable components > 1");
        }

        List<MethodDeclaration> clusteredTests = new ArrayList<>();

        // Get original test lines to maintain ordering
        List<String> originalTestLines = extractAllLines2(originalTest);

        // Process each group of dependent tests
        int num = 0;
        for (List<MethodDeclaration> group : dependentPurifiedTestGroups) {
            num++;
            // Skip empty groups
            if (group.isEmpty()) continue;

            if (group.size() == 1) {
                clusteredTests.add(group.get(0).clone());
                continue;
            }

            // Create a new merged test for this group
            MethodDeclaration mergedTest = new MethodDeclaration();
            mergedTest.setName(new SimpleName(group.get(0).getNameAsString() + "_testMerged_" + num));
            mergedTest.setAnnotations(originalTest.getAnnotations());
            mergedTest.setModifiers(originalTest.getModifiers());
            mergedTest.setType(originalTest.getType());
            mergedTest.setThrownExceptions(originalTest.getThrownExceptions());

            Set<String> uniqueLines = new LinkedHashSet<>(); // Use LinkedHashSet to maintain insertion order

            // First pass: collect all unique lines and assertions
            for (MethodDeclaration test : group) {
                List<String> testLines = extractAllLines2(test);
                uniqueLines.addAll(testLines);
            }

            // Sort unique lines based on their appearance in original test
            List<String> sortedUniqueLines = uniqueLines.stream()
                    .sorted((line1, line2) -> {
                        int index1 = originalTestLines.indexOf(line1);
                        int index2 = originalTestLines.indexOf(line2);
                        return Integer.compare(index1, index2);
                    })
                    .collect(Collectors.toList());

            // Combine sorted lines and assertions
            List<String> mergedLines = new ArrayList<>();
            mergedLines.addAll(sortedUniqueLines);

            // Update the merged test's body
            updateTestBody(mergedTest, mergedLines);
            clusteredTests.add(mergedTest);
        }

        return clusteredTests;
    }

    // Helper method to extract all lines from a test method
    private static List<String> extractAllLines(MethodDeclaration test) {
        List<String> lines = new ArrayList<>();
        // Implementation depends on how your test methods are structured
        // You'll need to extract both assertion and non-assertion lines
        lines.addAll(extractNonAssertionLines(test));
        lines.addAll(extractAssertionLines(test));
        return lines;
    }

    // Helper method to update the body of a test method
    private static void updateTestBody(MethodDeclaration test, List<String> newLines) {
        // Implementation depends on your AST manipulation library
        // This should replace the existing body with the new lines
        BlockStmt newBody = new BlockStmt();
        for (String line : newLines) {
            // Convert each line to a statement and add to the block
            // The exact implementation will depend on your parsing library
            try {
                Statement stmt = parseStatement(line);
                newBody.addStatement(stmt);
            } catch (Exception e) {
                System.out.println("Error parsing statement: " + line);
            }

        }
        test.setBody(newBody);
    }

    // Helper method to parse a string into a statement
    private static Statement parseStatement(String line) {
        // Implementation depends on your parsing library
        // This should convert a string into an AST Statement node
        return StaticJavaParser.parseStatement(line);
    }

    private static int countPotentialPutsInSimilarTestGroups(List<List<UnitTest>> similarTestGroups) {
        int count = 0;
        for (List<UnitTest> group : similarTestGroups) {
            if (group.size() > 1) {
                count++;
            }
        }
        return count;
    }

    static class ResultCreateRefreshedTestFilesInSandbox {
        int totalNewSeparatedTestsCreated;
        int totalNewPUTsCreated;
        int totalPotentialPuts;

        public ResultCreateRefreshedTestFilesInSandbox(int totalNewSeparatedTestsCreated, int totalNewPUTsCreated, int totalPotentialPuts) {
            this.totalNewSeparatedTestsCreated = totalNewSeparatedTestsCreated;
            this.totalNewPUTsCreated = totalNewPUTsCreated;
            this.totalPotentialPuts = totalPotentialPuts;
        }

    }
    public static ResultCreateRefreshedTestFilesInSandbox createRefreshedTestFilesInSandbox(List<TestFileResult> results) throws IOException {
        int totalNewSeparatedTestsCreated = 0;
        int totalNewPUTsCreated = 0;
        int totalPotentialPuts = 0;
        for (TestFileResult testClassResult : results) {
            if(testClassResult.pastaCount == 0) {
                continue;
            }
            // todo change the logic to create new file independent assertion cluster separated tests instead of purified => Check if done!
            ResultCreateNewClassFileWithSplittedTests resultx = createNewClassFileWithSplittedTests(testClassResult);
            String purifiedOutputFilePath = resultx.newClassFilePath;
            totalNewSeparatedTestsCreated += resultx.newSeparatedTests;
            // Purified file has separated tests of only the pasta tests from the original file.
            // Should also have others? I think it's fine.

            // PHASE II
            // Replace all the type 2 clones with their respective PUT
            CompilationUnit cu = configureJavaParserAndGetCompilationUnit(purifiedOutputFilePath);
            List<String> listTestMethods = extractTestMethodListFromCU(cu); // all purified tests
            HashMap<String, NodeList<Node>> statementNodesListMap = extractASTNodesForTestMethods(cu, listTestMethods);
            // type 2 clone detection
            List<List<UnitTest>> similarTestGroups = groupSimilarTests(listTestMethods, statementNodesListMap);
            int potentialPUTs = countPotentialPutsInSimilarTestGroups(similarTestGroups);
            totalPotentialPuts = totalPotentialPuts + potentialPUTs;
            System.out.println("Potential PUTs: " + potentialPUTs);
            if(potentialPUTs == 0) {
                System.out.println("No potential PUTs for file: " + purifiedOutputFilePath);
                continue;
            }

            List<MethodDeclaration> newPUTs = new ArrayList<>();
            try {
                newPUTs = retrofitSimilarTestsTogether(similarTestGroups, cu);
            } catch (Exception e) {
                System.out.println("Error Creating PUTs");
            }

            if(newPUTs.size() == 0) {
                System.out.println("ERROR?: Puts should be created: " + purifiedOutputFilePath);
            }
            else {
                System.out.println(newPUTs.size() + " new PUTs created for file: " + purifiedOutputFilePath);
                totalNewPUTsCreated = totalNewPUTsCreated + newPUTs.size()/2;
                createParameterizedTestFile(purifiedOutputFilePath, newPUTs, extractTestMethodsToExclude(similarTestGroups));
            }
        }
//        System.out.println("Total potential PUTs: " + totalPotentialPuts);
//        System.out.println("Total new separated tests created: " + totalNewSeparatedTestsCreated);
        return new ResultCreateRefreshedTestFilesInSandbox(totalNewSeparatedTestsCreated, totalNewPUTsCreated, totalPotentialPuts);
    }

    public static void fixAssertionPastaInRepo(String pathToJavaRepository) throws IOException {
        List<TestFileResult> results = getAssertionPastaResultsInRepo(pathToJavaRepository);
        // for each results in a file
        // create file with only pastas and todo: add all of top code
        ResultCreateRefreshedTestFilesInSandbox result = createRefreshedTestFilesInSandbox(results);
        System.out.println("Total new separated tests created: " + result.totalNewSeparatedTestsCreated);
        System.out.println("Total potential PUTs: " + result.totalPotentialPuts);
        System.out.println("Total new PUTs created: " + result.totalNewPUTsCreated);
    }

    public static void detectAssertionPastaAndGenerateReport(String pathToJavaRepository) throws IOException {
        // locates and read all the test files in the path folder
        // detects assertion pasta in each test file : use identifyAssertionPastas(inputFile);
        // generates a report file which has a table and results. 4 columns: Test File Name, Total Tests, Assertion Pasta Count, Assertion Pasta Percentage
        List<TestFileResult> results = getAssertionPastaResultsInRepo(pathToJavaRepository);
        generateReportAssertionPasta(results, pathToJavaRepository);
    }

    public static void detectSimilarTestsInRepoAndGenerateReport(String pathToJavaRepository) throws IOException {
        List<TestFileResult> results = new ArrayList<>();
        AtomicInteger count = new AtomicInteger();

        // Find all Java test files in the repository
        Files.walk(Paths.get(pathToJavaRepository))
                .filter(Files::isRegularFile)
                .filter(path -> isTestFile(path.toString()))
                .forEach(path -> {
                    try {
                        count.getAndIncrement();
                        System.out.println("Processing Class No: " + count);
                        System.out.println(path);
                        detectSimilarTestsInFile(path.toString());
                    } catch (Exception e) {
                        System.err.println("Error processing file: " + path);
                        e.printStackTrace();
                    }
                });

    }
    public static List<List<UnitTest>> detectSimilarTestsInFile(String inputFile) throws FileNotFoundException {
        // todo don't include methods which aren't Tests (no @Test)
        CompilationUnit cu = configureJavaParserAndGetCompilationUnit(inputFile);
        List<String> listTestMethods = extractTestMethodListFromCU(cu);
        HashMap<String, NodeList<Node>> statementNodesListMap = extractASTNodesForTestMethods(cu, listTestMethods);
        List<List<UnitTest>> similarTestGroups = groupSimilarTests(listTestMethods, statementNodesListMap);
        System.out.println("Confirming Total tests: " + listTestMethods.size());
        int redundantTests = listTestMethods.size() - similarTestGroups.size();
        System.out.println("Redundant Tests: " + redundantTests);
        TotalRedundantTests = TotalRedundantTests + redundantTests;
        System.out.println("Total similar test groups: " + similarTestGroups.size());
        for (List<UnitTest> group : similarTestGroups) {
            System.out.println("Group Size: " + group.size());
            if (group.size() > 1) {
                TotalNewPuts = TotalNewPuts + 1;
            }
            for (UnitTest test : group) {
                System.out.println(test.Name);
            }
            System.out.println("====================================");
        }
        System.out.println("============================================================================================================");
        return similarTestGroups;
    }
    public static String filterTestMethod(String testClassPath, String testMethodName) throws IOException {
        // Parse the provided Java test class
        CompilationUnit compilationUnit = StaticJavaParser.parse(new File(testClassPath));

        // Extract the test class
        ClassOrInterfaceDeclaration testClass = compilationUnit
                .getClassByName(Paths.get(testClassPath).getFileName().toString().replace(".java", ""))
                .orElseThrow(() -> new IllegalArgumentException("Test class not found"));

        // Create a modifiable copy of methods
        List<MethodDeclaration> methods = new ArrayList<>(testClass.getMethods());

        // Remove unwanted methods
        for (MethodDeclaration method : methods) {
            if (!isNeededMethod(method, testMethodName)) {
                testClass.getMembers().remove(method); // Remove from class
            }
        }

        // Create the new test class file
        String newTestClassName = testClass.getNameAsString() + testMethodName;
        testClass.setName(newTestClassName);
        String newFileName = newTestClassName + ".java";

        Path newFilePath = Paths.get(new File(testClassPath).getParent(), newFileName);
        try (FileWriter writer = new FileWriter(newFilePath.toFile())) {
            writer.write(compilationUnit.toString());
        }

        return newFilePath.toString();
    }

    private static boolean isNeededMethod(MethodDeclaration method, String testMethodName) {
        // Check if the method is the target test method
        if (method.getNameAsString().equals(testMethodName)) {
            return true;
        }

        // Check for annotations like @Before, @BeforeAll, @After, @AfterAll
        return method.getAnnotations().stream()
                .map(annotation -> annotation.getName().asString())
                .anyMatch(annotation -> annotation.equals("Before") || annotation.equals("BeforeAll")
                        || annotation.equals("After") || annotation.equals("AfterAll"));
    }

    public static List<String> extractTestMethodsToExclude(List<List<UnitTest>> similarTests) {
        List<String> excludedTests = new ArrayList<>();
        for (List<UnitTest> group : similarTests) {
            if (group.size() > 1) {
                for (int i = 0; i < group.size(); i++) {
                    excludedTests.add(group.get(i).Name);
                }
            }
        }
        return excludedTests;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Please provide the path to the input file as an argument.");
        }
        String inputFile = args[0];
        String operation = args[1];
        if(operation.equals("detect")) {
            detectAssertionPastaAndGenerateReport(inputFile);
        } else if (operation.equals("allRepos")) {
            String[] inputFiles = inputFile.split(","); // Split the comma-separated paths
            for (String file : inputFiles) {
                detectAssertionPastaAndGenerateReport(file.trim()); // Trim spaces to avoid errors
            }
        }
        else if (operation.equals("detectin")) {
            identifyAssertionPastas(inputFile);
        }
        else if (operation.equals("fixInRepo")) {
            fixAssertionPastaInRepo(inputFile);
        }
        else if (operation.equals("fixin")) {
            if(args.length < 3) {
                throw new IllegalArgumentException("Please provide the method name to fix as an argument.");
            }
            String method = args[2];
            String filteredTestFilePath = filterTestMethod(inputFile, method);
            System.out.println("Filtered test file created: " + filteredTestFilePath);
            String purifiedTestsFile = createPurifiedTestFile(filteredTestFilePath);
            System.out.println( "Purified test file created: " + purifiedTestsFile);

            CompilationUnit cu = configureJavaParserAndGetCompilationUnit(purifiedTestsFile);
            List<String> listTestMethods = extractTestMethodListFromCU(cu);
            HashMap<String, NodeList<Node>> statementNodesListMap = extractASTNodesForTestMethods(cu, listTestMethods);
            List<List<UnitTest>> similarTestGroups = groupSimilarTests(listTestMethods, statementNodesListMap);
            List<MethodDeclaration> newPUTs = retrofitSimilarTestsTogether(similarTestGroups, cu);
            String putsFile = createParameterizedTestFile(purifiedTestsFile, newPUTs, new ArrayList<>());
            createGPTEnhancedTestFile(putsFile, newPUTs);
        } else if(operation.equals("detectSimilarIn")) { // Similar tests to PUTify
            detectSimilarTestsInFile(inputFile);
        } else if(operation.equals("detectSimilar")) { // Similar tests to PUTify
            detectSimilarTestsInRepoAndGenerateReport(inputFile);
            System.out.println("Total Redundant Tests: " + TotalRedundantTests);
            System.out.println("Total New PUTs: " + TotalNewPuts);
        } else if(operation.equals("retrofitIn")) { // Similar tests to PUTs
            CompilationUnit cu = configureJavaParserAndGetCompilationUnit(inputFile);
            List<List<UnitTest>> similarTest = detectSimilarTestsInFile(inputFile);
            List<MethodDeclaration> newPUTs = retrofitSimilarTestsTogether(similarTest, cu);
            System.out.println("Total New PUTs: " + TotalNewPuts);
            String putsFile = createParameterizedTestFile(inputFile, newPUTs, extractTestMethodsToExclude(similarTest));
            System.out.println("Parameterized test file created: " + putsFile);
        } else if(operation.equals("retrofit")) {

        } else if(operation.equals("fix")) {
            String purifiedTestsFile = createPurifiedTestFile(inputFile);
            System.out.println( "Purified test file created: " + purifiedTestsFile);

            CompilationUnit cu = configureJavaParserAndGetCompilationUnit(purifiedTestsFile);
            // Later Quality check -> [runnable] [same coverage]
            List<String> listTestMethods = extractTestMethodListFromCU(cu);
            HashMap<String, NodeList<Node>> statementNodesListMap = extractASTNodesForTestMethods(cu, listTestMethods);
            List<List<UnitTest>> similarTestGroups = groupSimilarTests(listTestMethods, statementNodesListMap);
            // ToDo : Impl logic to merge similar tests together
            // create map: similarTestGroups - > lists of params for each group

            List<MethodDeclaration> newPUTs = retrofitSimilarTestsTogether(similarTestGroups, cu);
            String putsFile = createParameterizedTestFile(purifiedTestsFile, newPUTs, new ArrayList<>());
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







