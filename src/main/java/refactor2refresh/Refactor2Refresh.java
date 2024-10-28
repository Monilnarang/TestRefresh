package refactor2refresh;

import java.nio.file.Path;
import java.nio.file.Paths;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Refactor2Refresh {
    // Parameterizer
    protected static final File DEFAULT_OUTPUT_DIR = new File("./z-out-retrofit/");
    private static File outputDir = DEFAULT_OUTPUT_DIR;

    private static String GPT_PROMPT_VALUE_SETS = "Consider the below value sets of a parameterised unit test and the test itself written in Java.\\n\" +\n" +
            "                \"Could you please help me add more value sets to this test method so as to increase the coverage, cover the edge cases, and reveal bugs in the source code.\\n\" +\n" +
            "                \"Please try to generate minimum number of such value sets. And only output the updated java code.";

    private static String GPT_PROMPT_ATOMIZED_TESTS = "A backward slice – simply a version of the original program with some parts missing – can be compiled and executed. An important property of any backward slice is that it preserves the effect of the original program on the variable chosen at the selected point of interest within the program.\n" +
            "\n" +
            "Consider the below Java test file. Could you please generate backward slices for every assertion present in the file. And reply with a new test file with atomised tests created from those slices. \n" +
            "\n" +
            "More rules: Only reply with Java code and nothing else. To name the new atomic methods, use the current names and add _1, _2 .. suffix. Don't add any other new code or new text which was not present in the actual file. For any code which is commented keep it as it is in the new file" +
            "Here is the test file to do for: ";

    protected static String getDisclaimer(CompilationUnit.Storage s) {
        return String.format("\n\tThis file was automatically generated as part of a slice with criterion");
    }

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
            System.out.println("Method found:");
            System.out.println(method);
        } else {
            System.out.println("Method 'test1' not found.");
        }
        NodeList<Node> statementsNodes = new NodeList<>();
        for (int i=0;i<statements.size();i++) {
            // create copy of statements.get(i) and then add that copy to statementsNodes
            statementsNodes.add(statements.get(i).clone());
        }
        return statementsNodes;
    }

    static boolean areTestsSimilarEnoughToBeRetrofitted(NodeList<Node> statementNodes1, NodeList<Node> statementNodes2) {
        TreeMap<Integer, ArrayList<String>> paramWiseValueSets = new TreeMap<Integer, ArrayList<String>>();

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
            System.out.println("Tests are similar enough to be retrofitted together");
            return true;
        } else {
            System.out.println("Tests can't be retrofitted together");
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

    static List<MethodDeclaration> retrofitSimilarTestsTogether(List<List<UnitTest>> similarTestGroups, CompilationUnit cu) {
        List<MethodDeclaration> newPUTsList = new ArrayList<>();
        for( List<UnitTest> group : similarTestGroups) {
            TreeMap<Integer, ArrayList<String>> parameter_to_values_map = new TreeMap<Integer, ArrayList<String>>();
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
                    ArrayList<String> values = parameter_to_values_map.get(i + 1);  // TreeMap keys start from 1 in this example
                    if (values != null && !values.isEmpty()) {
                        // Initial value is the first element of the array
                        String initialValue = values.get(0);
                        // Construct parameter name
                        String parameterName = "param" + (i + 1);
                        // Print the initial value and parameter name
//                    System.out.println("Initial value: " + initialValue + ", Parameter name: " + parameterName);
                        method.addParameter(int.class, parameterName);
                        // Traverse the method body to find and replace the hardcoded value
                        method.findAll(LiteralStringValueExpr.class).forEach(literalExpr -> {
                            if (literalExpr.getValue().equals(initialValue)) {
                                literalExpr.replace(new NameExpr(parameterName));
                            }
                        });
                    }
                }
                // Remove the @Test annotation
                method.getAnnotations().removeIf(annotation -> annotation.getNameAsString().equals("Test"));
                // Add the @ParameterizedTest annotation
                method.addAnnotation(new MarkerAnnotationExpr("ParameterizedTest"));
                // change name of methods to "parameterisedTest"
                method.setName(newTestName);
                NormalAnnotationExpr csvSourceAnnotation = new NormalAnnotationExpr();
                // Construct the CSV source values
                List<String> csvRows = IntStream.range(0, parameter_to_values_map.get(1).size())
                        .mapToObj(i -> parameter_to_values_map.values().stream()
                                .map(list -> list.get(i))
                                .collect(Collectors.joining(", ")))
                        .collect(Collectors.toList());

                String csvSourceValues = csvRows.stream()
                        .collect(Collectors.joining("\", \"", "\"", "\""));

                // Add the @CsvSource annotation
                csvSourceAnnotation.setName("CsvSource");
                csvSourceAnnotation.addPair("value", "{" + csvSourceValues + "}");

                method.addAnnotation(csvSourceAnnotation);

                // Print the modified method
                System.out.println("Modified method:");
                System.out.println(method);
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

        // Create a new file with GPT-modified methods
        try (BufferedWriter writerGPT = new BufferedWriter(new FileWriter(outputFilePathGPT))) {
            for (MethodDeclaration method : newMethods) {
                // Generate GPT-modified method
                String answer = model.generate(GPT_PROMPT_VALUE_SETS + method);
                System.out.println("Modified Method with GPT Generated Value Sets \n" + answer);

                // Write each modified method to the GPT file
                writerGPT.write(answer);
                writerGPT.newLine();
                writerGPT.newLine(); // Add space between methods
            }
            System.out.println("GPT file created successfully at: " + outputFilePathGPT);
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
        ClassOrInterfaceDeclaration originalClass = compilationUnit.getClassByName(originalFile.getName().replace("_Atomized.java", ""))
                .orElseThrow(() -> new IllegalArgumentException("No class found in the file."));

        // Create a new class name with the suffix "Parameterized"
        String newClassName = originalClass.getNameAsString() + "_Parameterized";

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
        String newFileName = originalFile.getName().replace(".java", "Parameterized.java");
        Path newFilePath = Paths.get(originalFile.getParent(), newFileName);

        // Write the updated CompilationUnit to the new file
        try (FileOutputStream outputStream = new FileOutputStream(newFilePath.toFile())) {
            outputStream.write(compilationUnit.toString().getBytes());
        }

        System.out.println("Parameterized test file created at: " + newFilePath.toString());
        return newFilePath.toString();
    }
    static void oldUnorganisedCode(String input_file) throws IOException {
        CompilationUnit cu = configureJavaParserAndGetCompilationUnit(input_file);

        System.out.println("****************************************************************");
        System.out.println("****************************************************************");
        System.out.println("****************************************************************");


        NodeList<Node> statementNodes1 = getASTStatementsForMethodByName(cu, "test1");
        NodeList<Node> statementNodes2 = getASTStatementsForMethodByName(cu, "test2");

        // Get Trie Like Nodes from AST
        TreeMap<Integer, ArrayList<String>> parameter_to_values_map = new TreeMap<Integer, ArrayList<String>>();

        HashMap<String, SlicingUtils.Variable> variableMap1 = new HashMap<String, SlicingUtils.Variable>();
        HashMap<String, SlicingUtils.Variable> variableMap2 = new HashMap<String, SlicingUtils.Variable>();
        HashMap<String, Integer> hardcodedMap1 = new HashMap<String, Integer>();
        HashMap<String, Integer> hardcodedMap2 = new HashMap<String, Integer>();

        ArrayList<TrieLikeNode> trie1 = SlicingUtils.createTrieFromStatements(statementNodes1, hardcodedMap1, parameter_to_values_map);
        ArrayList<TrieLikeNode> trie2 = SlicingUtils.createTrieFromStatements(statementNodes2, hardcodedMap2, parameter_to_values_map);
        SlicingUtils slicingUtils = new SlicingUtils();
        slicingUtils.populateVariableMapFromTrie(trie1, variableMap1);
        slicingUtils.populateVariableMapFromTrie(trie2, variableMap2);
        HashMap<String, String> crossVariableMap = new HashMap<String, String>();
        if(SlicingUtils.compareTrieLists(trie1, trie2, crossVariableMap)) {
            System.out.println("Tests are similar enough to be retrofitted together");
        } else {
            System.out.println("Tests can't be retrofitted together");
            return;
        }

        String packagePath = cu.getPackageDeclaration().map(NodeWithName::getNameAsString).orElse("").replace(".", "/");
        File packageDir = new File(outputDir, packagePath);
        packageDir.mkdirs();
        // update below
        CompilationUnit cu2 = StaticJavaParser.parse(new File("/Users/monilnarang/Documents/Repos/Research/JavaSlicer/examples/AssertionPasta/Pasta.java"));

        Optional<MethodDeclaration> methodOpt = cu2.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("test1"))
                .findFirst();
        if (methodOpt.isPresent()) {
            MethodDeclaration method = methodOpt.get();

            // add parameters : replace hardcoded and add in signature
            for (int i = 0; i < parameter_to_values_map.size(); i++) {
                ArrayList<String> values = parameter_to_values_map.get(i + 1);  // TreeMap keys start from 1 in this example
                if (values != null && !values.isEmpty()) {
                    // Initial value is the first element of the array
                    String initialValue = values.get(0);
                    // Construct parameter name
                    String parameterName = "param" + (i + 1);
                    // Print the initial value and parameter name
//                    System.out.println("Initial value: " + initialValue + ", Parameter name: " + parameterName);
                    method.addParameter(int.class, parameterName);
                    // Traverse the method body to find and replace the hardcoded value
                    method.findAll(LiteralStringValueExpr.class).forEach(literalExpr -> {
                        if (literalExpr.getValue().equals(initialValue)) {
                            literalExpr.replace(new NameExpr(parameterName));
                        }
                    });
                }
            }

            // Remove the @Test annotation
            method.getAnnotations().removeIf(annotation -> annotation.getNameAsString().equals("Test"));
            // Add the @ParameterizedTest annotation
            method.addAnnotation(new MarkerAnnotationExpr("ParameterizedTest"));
            // change name of methods to "parameterisedTest"
            method.setName("parameterisedTest");
            NormalAnnotationExpr csvSourceAnnotation = new NormalAnnotationExpr();
            // Construct the CSV source values
            List<String> csvRows = IntStream.range(0, parameter_to_values_map.get(1).size())
                    .mapToObj(i -> parameter_to_values_map.values().stream()
                            .map(list -> list.get(i))
                            .collect(Collectors.joining(", ")))
                    .collect(Collectors.toList());

            String csvSourceValues = csvRows.stream()
                    .collect(Collectors.joining("\", \"", "\"", "\""));

            // Add the @CsvSource annotation
            csvSourceAnnotation.setName("CsvSource");
            csvSourceAnnotation.addPair("value", "{" + csvSourceValues + "}");

            method.addAnnotation(csvSourceAnnotation);

            // Print the modified method
            System.out.println("Modified method:");
            System.out.println(method);
        } else {
            System.out.println("Method 'parameterisedTest' not found.");
        }

        // Find all methods in the compilation unit and delete everything except the method we modified
        // delete everything except the method with name parameterisedTest
        List<MethodDeclaration> methods = cu2.findAll(MethodDeclaration.class);
        methods.stream()
                .filter(method -> !method.getNameAsString().equals("parameterisedTest"))
                .forEach(method -> method.remove());


        methodOpt = cu2.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("parameterisedTest"))
                .findFirst();
        if (methodOpt.isPresent()) {
            MethodDeclaration method = methodOpt.get();
            // call GPT to generate the value sets
            ChatLanguageModel model = OpenAiChatModel.builder()
                    .apiKey(ApiKeys.OPENAI_API_KEY)
                    .modelName("gpt-4o-mini")
                    .build();

            String answer = model.generate(GPT_PROMPT_VALUE_SETS + method);
            System.out.println("Modified Method with GPT Generated Value Sets \n" + answer);
        }

        File javaFile = new File(packageDir, cu.getStorage().get().getFileName());
        try (PrintWriter pw = new PrintWriter(javaFile)) {
            pw.print(new BlockComment(getDisclaimer(cu2.getStorage().get())));
//            LiteralStringValueExpr intLiteralExpr = (LiteralStringValueExpr) cu2.getChildNodes().get(1).getChildNodes().get(3).getChildNodes().get(3).getChildNodes().get(0).getChildNodes().get(0).getChildNodes().get(0).getChildNodes().get(2).getChildNodes().get(1);
//            intLiteralExpr.setValue("7");
            pw.print(cu2);
        } catch (FileNotFoundException e) {
            System.err.println("Could not write file " + javaFile);
        }
    }

    static String generateNewFileWithAtomizeTests(String inputFilePath) throws FileNotFoundException {
        try {
            // Read the content of the input Java test file
            System.out.println("Reading input file: " + inputFilePath);
            String fileContent = new String(Files.readAllBytes(Paths.get(inputFilePath)));


            // Build the OpenAiChatModel instance using your OpenAI API key
            System.out.println("Creating OpenAI model");
            ChatLanguageModel model = OpenAiChatModel.builder()
                    .apiKey(ApiKeys.OPENAI_API_KEY)
                    .modelName("gpt-4o-mini")
                    .build();

            // Combine the prompt with the test file content
            String fullPrompt = GPT_PROMPT_ATOMIZED_TESTS + "\n\n" + fileContent;

            // Send the prompt to the model and get the atomized test cases
            System.out.println("Running GPT model to atomize test cases");
            String atomizedTests = model.generate(fullPrompt);
            atomizedTests = atomizedTests.replace("```java", "").replace("```", "").trim();

            // Generate the output file path
            String outputFilePath = inputFilePath.replace(Paths.get(inputFilePath).getFileName().toString(), Paths.get(inputFilePath).getFileName().toString().replace(".java", "_Atomized.java"));
            // Write the generated atomized tests to the output file
            System.out.println("Writing atomized test cases to output file");
            Files.write(Paths.get(outputFilePath), atomizedTests.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            System.out.println("Atomized test cases have been written to " + outputFilePath);
            return outputFilePath;

        } catch (IOException e) {
            System.err.println("Error reading/writing file: " + e.getMessage());
            throw new RuntimeException(e);
        } catch (RuntimeException e) {
            System.err.println("Error with GPT model: " + e.getMessage());
        }
        return null;
    }
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("Please provide the path to the input file as an argument.");
        }
        String inputFile = args[0];
        // oldUnorganisedCode(inputFile);

        String atomizedTestsFile = generateNewFileWithAtomizeTests(inputFile);
        // Todo: Quality check -> test if the new test file is runnable and has the exact same coverage.


        CompilationUnit cu = configureJavaParserAndGetCompilationUnit(atomizedTestsFile);
        List<String> listTestMethods = extractMethodListFromCU(cu);
        HashMap<String, NodeList<Node>> statementNodesListMap = extractASTNodesForTestMethods(cu, listTestMethods);
        List<List<UnitTest>> similarTestGroups = groupSimilarTests(listTestMethods, statementNodesListMap);
        List<MethodDeclaration> newPUTs = retrofitSimilarTestsTogether(similarTestGroups, cu);
        String putsFile = createParameterizedTestFile(atomizedTestsFile, newPUTs);
        createGPTEnhancedTestFile(putsFile, newPUTs);
    }
}


