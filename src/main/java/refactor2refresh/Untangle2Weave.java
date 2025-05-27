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

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.FileOutputStream;

public class Untangle2Weave {

    public static HashMap<String, TestAnalytics> analyticsMap = new HashMap<>();
    static Map<Integer, Integer> separableComponentFrequency = new HashMap<>();
    // key => TestClass#TestMethod
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
        CompilationUnit cu;
        try{
            cu = StaticJavaParser.parse(new File(filePath));
        } catch (Exception e) {
            System.out.println("Error parsing file: " + filePath);
            e.printStackTrace();
            throw new RuntimeException("Error parsing file: " + filePath, e);
        }

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
        // check if a group doesn't have any hardcoded value them split them as separate groups.
        return processTestGroups(similarTestGroups);
    }

    public static List<List<UnitTest>> processTestGroups(List<List<UnitTest>> similarTestGroups) {
        List<List<UnitTest>> processedGroups = new ArrayList<>();

        for (List<UnitTest> group : similarTestGroups) {
            // If group has only one test or is empty, just add it as is
            if (group == null || group.size() <= 1) {
                processedGroups.add(group);
                continue;
            }

            boolean groupHasLiterals = false;

            // Check if any test in the group contains literals
            for (UnitTest test : group) {
                if (containsLiterals(test.Statements)) {
                    groupHasLiterals = true;
                    break;
                }
            }

            if (groupHasLiterals) {
                // If group has literals, keep it as is
                processedGroups.add(group);
            } else {
                // If group doesn't have literals, split each test into its own group
                for (UnitTest test : group) {
                    List<UnitTest> singleTestGroup = new ArrayList<>();
                    singleTestGroup.add(test);
                    processedGroups.add(singleTestGroup);
                }
            }
        }

        return processedGroups;
    }

    public static boolean containsLiterals(NodeList<Node> statements) {
        if (statements == null || statements.isEmpty()) {
            return false;
        }

        // Create a visitor that checks for literals
        LiteralCheckVisitor literalVisitor = new LiteralCheckVisitor();

        // Visit each statement
        for (Node statement : statements) {
            statement.accept(literalVisitor, null);
            if (literalVisitor.hasFoundLiteral()) {
                return true;
            }
        }

        return false;
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
        int dataSize = parameter_to_values_map.firstEntry().getValue().size();  // Assuming at least one parameter exists

        // For each test case, create an arguments() call
        for (int i = 0; i < dataSize; i++) {
            MethodCallExpr argumentsCall = new MethodCallExpr();
            argumentsCall.setName("arguments");

            for (Map.Entry<Integer, ArrayList<LiteralExpr>> entry : parameter_to_values_map.entrySet()) {
                ArrayList<LiteralExpr> values = entry.getValue();
                if (i < values.size()) {
                    LiteralExpr value = values.get(i);
                    argumentsCall.addArgument(value.clone());  // Clone to avoid modifying original
                }
            }

            streamOfCall.addArgument(argumentsCall);
        }

        // Add the return statement to the method body
        body.addStatement(new ReturnStmt(streamOfCall));
        providerMethod.setBody(body);

        // Add annotations if needed (e.g., @Test would not be appropriate here)
        return providerMethod;
    }


    static class PathNode {
        TrieLikeNode node;
        int path;

        PathNode(TrieLikeNode node, int path) {
            this.node = node;
            this.path = path;
        }
    }

    public static TreeMap<Integer, ArrayList<LiteralExpr>> path_to_values_map(ArrayList<ArrayList<TrieLikeNode>> similar_tries) {
        // Initialize the result map
        TreeMap<Integer, ArrayList<LiteralExpr>> resultMap = new TreeMap<>();
        int pathCounter = 0;

        // If there are no tries to process, return the empty map
        if (similar_tries == null || similar_tries.isEmpty()) {
            return resultMap;
        }

        // Process the first trie to establish paths
        ArrayList<TrieLikeNode> firstTrie = similar_tries.get(0);

        // For each root node in the first trie
        for (int rootIndex = 0; rootIndex < firstTrie.size(); rootIndex++) {
            TrieLikeNode root = firstTrie.get(rootIndex);

            // Perform BFS starting from this root
            Queue<PathNode> queue = new LinkedList<>();
            queue.add(new PathNode(root, pathCounter++)); // Start with the root path

            while (!queue.isEmpty()) {
                PathNode current = queue.poll();
                TrieLikeNode node = current.node;
                int path = current.path;

                // If this is a literal node, store its path and value
                if (node.type == StatementType.LITERAL) {
                    // Create a unique path ID
                    int pathId = path;

                    // Create a new entry for this path with the literal value
                    ArrayList<LiteralExpr> values = new ArrayList<>();
                    // Convert the string value to a LiteralExpr (this will need to be adapted
                    // based on the actual type of literal)
                    LiteralExpr literalExpr = createLiteralExpr(node.value);
                    values.add(literalExpr);
                    resultMap.put(pathId, values);
                }

                // Add all children to the queue with updated paths
                for (int i = 0; i < node.children.size(); i++) {
                    // Create a new path by appending the child index
                    int newPath = pathCounter++;
                    queue.add(new PathNode(node.children.get(i), newPath));
                }
            }
        }

        // Process all remaining tries to collect their literal values for the same paths
        for (int trieIndex = 1; trieIndex < similar_tries.size(); trieIndex++) {
            ArrayList<TrieLikeNode> currentTrie = similar_tries.get(trieIndex);

            // For each root node in the current trie
            int pathCounterSimilar = 0;
            for (int rootIndex = 0; rootIndex < currentTrie.size(); rootIndex++) {
                TrieLikeNode root = currentTrie.get(rootIndex);

                // Perform BFS starting from this root
                Queue<PathNode> queue = new LinkedList<>();
                queue.add(new PathNode(root, pathCounterSimilar++)); // Start with the root path



                while (!queue.isEmpty()) {
                    PathNode current = queue.poll();
                    TrieLikeNode node = current.node;
                    int path = current.path;

                    // If this is a literal node, find its corresponding path
                    if (node.type == StatementType.LITERAL) {
                        // Create the same unique path ID
                        int pathId = path;

                        // If this path exists in our map, add this trie's literal value
                        if (resultMap.containsKey(pathId)) {
                            ArrayList<LiteralExpr> values = resultMap.get(pathId);
                            LiteralExpr literalExpr = createLiteralExpr(node.value);
                            values.add(literalExpr);
                        }
                    }

                    // Add all children to the queue with updated paths
                    for (int i = 0; i < node.children.size(); i++) {
                        // Create a new path by appending the child index
                        int newPath = pathCounterSimilar++;
                        queue.add(new PathNode(node.children.get(i), newPath));
                    }
                }
            }
        }

        return resultMap;
    }

    private static LiteralExpr createLiteralExpr(String value) {
        // Check if it's null
        if (value == null || value.equals("null")) {
            return new NullLiteralExpr();
        }

        // Check if it's a boolean
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return new BooleanLiteralExpr(Boolean.parseBoolean(value));
        }

        // Check if it's a character (enclosed in single quotes)
        if (value.length() >= 3 && value.startsWith("'") && value.endsWith("'")) {
//            return new CharLiteralExpr(value.substring(1, value.length() - 1));
            return new StringLiteralExpr(value);
        }

        // Check if it's a string (enclosed in double quotes)
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return new StringLiteralExpr(value.substring(1, value.length() - 1));
        }

        // Check if it's a long literal (must check before regular number parsing)
        if (value.endsWith("L") || value.endsWith("l")) {
            // Only try to parse if it's not a special case like "C/C=C/?Cl"
            if (!value.contains("/")) {
                try {
                    // Remove the 'L' suffix for parsing
                    String numValue = value.substring(0, value.length() - 1);

                    // Handle hex values manually since Long.decode() fails for negative hex values
                    if (numValue.toLowerCase().startsWith("0x")) {
                        String hexPart = numValue.substring(2);
                        // Check if it's a valid hex string and within 64-bit range
                        if (hexPart.matches("[0-9a-fA-F]{1,16}")) {
                            // Parse as unsigned long, then let Java handle the two's complement conversion
                            Long.parseUnsignedLong(hexPart, 16);
                            return new LongLiteralExpr(value);
                        }
                    } else {
                        // For non-hex values, use decode as before
                        Long.decode(numValue);
                        return new LongLiteralExpr(value);
                    }
                } catch (NumberFormatException e) {
                    // If it fails parsing as a long, treat it as a string
                    return new StringLiteralExpr(value);
                }
            } else {
                // This is a string like "C/C=C/?Cl" that ends with 'l'
                return new StringLiteralExpr(value);
            }
        }

        // Check if it's an integer
        try {
            Integer.parseInt(value);
            return new IntegerLiteralExpr(value);
        } catch (NumberFormatException e) {
            // Not an integer, continue to other checks
        }

        // Check if it's a double
        try {
            Double.parseDouble(value);
            return new DoubleLiteralExpr(value);
        } catch (NumberFormatException e) {
            // Not a valid number format
        }

        // Default to string literal if no other type matched
        return new StringLiteralExpr(value);
    }

    private static class PathTracker {
        Node node;
        int path;

        PathTracker(Node node, int path) {
            this.node = node;
            this.path = path;
        }
    }

    // Helper method to replace literal at specific path
    private static void replaceLiteralAtPath(MethodDeclaration method, int pathId, String parameterName) {
        // Extract path components from pathId
        int rootIndex = pathId / 10000;
        int pathCounter = pathId % 10000;
        int actualPath = rootIndex; // The path without the counter
        int newPathCounter=0;

        // Find the target literal using BFS with path tracking
        Queue<PathTracker> queue = new LinkedList<>();

        // Start BFS from method body (assuming it's the root)
        if (method.getBody().isPresent()) {
            BlockStmt body = method.getBody().get();
            queue.add(new PathTracker(body, newPathCounter++));

            int currentPathCounter = 0;

            while (!queue.isEmpty()) {
                PathTracker current = queue.poll();
                Node node = current.node;
                int currentPath = current.path;

                // If this is a LiteralExpr at the target path
                if (node instanceof LiteralExpr) {
                    // && currentPath == pathId+1
//                    if (currentPathCounter == pathCounter) {
                        // Replace this specific literal
                        ((LiteralExpr) node).replace(new NameExpr(parameterName));
                        return;
//                    }
//                    currentPathCounter++;
                }

                // Add children to queue with updated paths
                List<Node> children = node.getChildNodes();
                for (int i = 0; i < children.size(); i++) {
                    int newPath = newPathCounter++;
                    queue.add(new PathTracker(children.get(i), newPath));
                }
            }
        }
    }

    static List<MethodDeclaration> retrofitSimilarTestsTogether(List<List<UnitTest>> similarTestGroups, CompilationUnit cu) {
        List<MethodDeclaration> newPUTsList = new ArrayList<>();
        for( List<UnitTest> group : similarTestGroups) {
            if(group.size() < 2) {
                continue;
            }
            TreeMap<Integer, ArrayList<LiteralExpr>> parameter_to_values_map_old = new TreeMap<Integer, ArrayList<LiteralExpr>>();
            String firstTestName = group.get(0).Name;
            String newTestName = generateParameterizedTestName(group); // use this for Assertion Pasta
//            String newTestName = firstTestName + "_Parameterized";
            ArrayList<ArrayList<TrieLikeNode>> tries = new ArrayList<ArrayList<TrieLikeNode>>();
            System.out.println("Group tests: ");
            for( UnitTest unitTest : group) {
                System.out.println("Test Name: " + unitTest.Name);
                HashMap<String, SlicingUtils.Variable> variableMap = new HashMap<String, SlicingUtils.Variable>();
                HashMap<String, Integer> hardcodedMap = new HashMap<String, Integer>();
                tries.add(SlicingUtils.createTrieFromStatementsNew(unitTest.Statements, hardcodedMap, parameter_to_values_map_old));
            }

            TreeMap<Integer, ArrayList<LiteralExpr>> parameter_to_values_map = path_to_values_map(tries);
            // extract method 1 from cu
            Optional<MethodDeclaration> methodOpt = cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(firstTestName))
                    .findFirst();
            if (methodOpt.isPresent()) {
                MethodDeclaration method = methodOpt.get();

                // add parameters : replace hardcoded and add in signature
//                int index=0;
//                for (Map.Entry<Integer, ArrayList<LiteralExpr>> entry : parameter_to_values_map.entrySet()) {
//                    ArrayList<LiteralExpr> values = entry.getValue();
//                    if (values != null && !values.isEmpty()) {
//                        LiteralExpr initialValue = values.get(0);
//                        String parameterName = "param" + (index + 1);
//                        Class<?> parameterType = inferType(initialValue);
//                        method.addParameter(parameterType, parameterName);
//                        List<LiteralExpr> literalExprs = method.findAll(LiteralExpr.class);
//                        for (LiteralExpr literalExpr : literalExprs) {
//                            if (getLiteralValue(literalExpr).equals(getLiteralValue(initialValue))) {
//                                literalExpr.replace(new NameExpr(parameterName));
//                            }
//                        }
//                        index++;
//                    }
//                }

                int index = 0;
                for (Map.Entry<Integer, ArrayList<LiteralExpr>> entry : parameter_to_values_map.entrySet()) {
                    Integer pathId = entry.getKey();
                    ArrayList<LiteralExpr> values = entry.getValue();

                    if (values != null && !values.isEmpty()) {
                        LiteralExpr initialValue = values.get(0);
                        String parameterName = "param" + (index + 1);
                        // Use path-based replacement instead of direct value matching
                        replaceLiteralAtPath(method, pathId, parameterName);

                        index++;
                    }
                }
                index=0;
                for (Map.Entry<Integer, ArrayList<LiteralExpr>> entry : parameter_to_values_map.entrySet()) {
                    ArrayList<LiteralExpr> values = entry.getValue();

                    if (values != null && !values.isEmpty()) {
                        LiteralExpr initialValue = values.get(0);
                        String parameterName = "param" + (index + 1);
                        Class<?> parameterType = inferType(initialValue);
                        method.addParameter(parameterType, parameterName);
                        index++;
                    }
                }
                method.getAnnotations().removeIf(annotation -> annotation.getNameAsString().equals("Test"));
                method.addAnnotation(new MarkerAnnotationExpr("ParameterizedTest"));
                method.setName(newTestName);

                // Create the MethodSource annotation
                String methodSourceName =  "Provider_" + newTestName;
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

    private static void performSlicingDelete(MethodDeclaration method, MethodCallExpr assertion, Map<String, Set<String>> beforeMethodDependencies) {
        // input method is an atomized test, with only one assertion and all the lines after it removed

        // Collect variables used in the assertion
        Set<String> requiredVariables = getVariablesUsedInAssertion(assertion);

        // Add the main object being asserted on to required variables
        assertion.getScope().ifPresent(scope -> {
            if (scope instanceof MethodCallExpr) {
                MethodCallExpr methodScope = (MethodCallExpr) scope;
                methodScope.getScope().ifPresent(innerScope -> {
                    requiredVariables.add(innerScope.toString());
                });
            }
        });

        // Track method calls on required objects
        Set<String> requiredObjectMethodCalls = new HashSet<>();

        // Identify method calls on required objects in the assertion
        findObjectMethodCalls(assertion, requiredVariables, requiredObjectMethodCalls);

        // Expand required variables based on beforeMethodDependencies
        Set<String> expandedRequiredVariables = expandVariablesUsingBeforeDependencies(requiredVariables, beforeMethodDependencies);

        // Process once to identify all control objects
        Set<String> controlObjects = new HashSet<>(requiredVariables);
        List<Statement> statements = method.getBody().orElseThrow().getStatements();
        for (Statement stmt : statements) {
            if (stmt.isExpressionStmt()) {
                ExpressionStmt exprStmt = stmt.asExpressionStmt();
                Expression expr = exprStmt.getExpression();

                // Identify assignments to control objects
                if (expr.isAssignExpr()) {
                    AssignExpr assignExpr = expr.asAssignExpr();
                    String target = assignExpr.getTarget().toString();
                    if (expandedRequiredVariables.contains(target)) {
                        // If this is a reassignment of a control object, add it
                        controlObjects.add(target);
                    }
                }
            }
        }

        // Iterate over statements in reverse order to determine which ones to keep
        Set<Statement> statementsToKeep = new HashSet<>();
        for (int i = statements.size() - 1; i >= 0; i--) {
            Statement stmt = statements.get(i);
            boolean keepStatement = false;

            // Check if the statement is an expression statement
            if (stmt.isExpressionStmt()) {
                ExpressionStmt exprStmt = stmt.asExpressionStmt();
                Expression expr = exprStmt.getExpression();

                // Check if the statement involves a method call on a required object
                if (expr.isMethodCallExpr()) {
                    MethodCallExpr methodCallExpr = expr.asMethodCallExpr();

                    // If this is the assertion, keep it
                    if (methodCallExpr.equals(assertion)) {
                        keepStatement = true;
                    }
                    // Check if method is called on a control object
                    else if (methodCallExpr.getScope().isPresent()) {
                        String scopeStr = methodCallExpr.getScope().get().toString();
                        String methodName = methodCallExpr.getNameAsString();

                        // Keep methods that operate on control objects or have control objects as arguments
                        if (controlObjects.contains(scopeStr) ||
                                methodCallExpr.getArguments().stream().anyMatch(arg ->
                                        getVariablesUsedInExpression(arg).stream().anyMatch(controlObjects::contains))) {
                            keepStatement = true;
                            // Add all variables used in the method call
                            Set<String> newVars = getVariablesUsedInExpression(methodCallExpr);
                            expandedRequiredVariables.addAll(newVars);
                            expandedRequiredVariables.addAll(expandVariablesUsingBeforeDependencies(newVars, beforeMethodDependencies));
                        }
                        // Keep if it calls a method on a required object or uses a required variable
                        else if (checkMethodExpressionsAndRequiredObjects(methodCallExpr, requiredObjectMethodCalls) ||
                                methodCallExpr.getArguments().stream().anyMatch(arg ->
                                        getVariablesUsedInExpression(arg).stream().anyMatch(expandedRequiredVariables::contains))) {
                            keepStatement = true;
                            // Add all variables used in the method call
                            Set<String> newVars = getVariablesUsedInExpression(methodCallExpr);
                            expandedRequiredVariables.addAll(newVars);
                            expandedRequiredVariables.addAll(expandVariablesUsingBeforeDependencies(newVars, beforeMethodDependencies));
                        }
                    } else if(methodCallExpr.getArguments().size() > 0) {
//                        for(int j = 0; j < methodCallExpr.getArguments().size(); j++) {
//                            Expression arg = methodCallExpr.getArgument(j);
//                            Set<String> argVars = getVariablesUsedInExpression(arg);
//                            if (argVars.stream().anyMatch(expandedRequiredVariables::contains)) {
//                                keepStatement = true;
//                                expandedRequiredVariables.addAll(argVars);
//                                expandedRequiredVariables.addAll(expandVariablesUsingBeforeDependencies(argVars, beforeMethodDependencies));
//                                break;
//                            }
//                        }
                    }
                }

                // If the expression is an assignment, check the variable it defines
                if (expr.isAssignExpr()) {
                    AssignExpr assignExpr = expr.asAssignExpr();
                    String varName = assignExpr.getTarget().toString();

                    // Keep if it defines a required variable
                    if (expandedRequiredVariables.contains(varName)) {
                        keepStatement = true;
                        expandedRequiredVariables.addAll(getVariablesUsedInExpression(assignExpr.getValue()));
                        // Add expanded dependencies
                        expandedRequiredVariables.addAll(
                                expandVariablesUsingBeforeDependencies(
                                        getVariablesUsedInExpression(assignExpr.getValue()),
                                        beforeMethodDependencies
                                )
                        );
                    }
                    // Or if any method within the assignment is relevant
                    else {
                        List<MethodCallExpr> methodCallExprs = assignExpr.findAll(MethodCallExpr.class);
                        boolean anyMethodRelevant = methodCallExprs.stream()
                                .anyMatch(methodCallExpr -> {
                                    if (checkMethodExpressionsAndRequiredObjects(methodCallExpr, requiredObjectMethodCalls)) {
                                        return true;
                                    }
                                    // Also check if method modifies state of a control object
                                    if (methodCallExpr.getScope().isPresent()) {
                                        String scope = methodCallExpr.getScope().get().toString();
                                        String methodName = methodCallExpr.getNameAsString();
                                        return controlObjects.contains(scope);
                                    }
                                    return false;
                                });

                        if (anyMethodRelevant) {
                            keepStatement = true;
                            // Add all variables
                            Set<String> newVars = getVariablesUsedInExpression(assignExpr.getValue());
                            expandedRequiredVariables.addAll(newVars);
                            expandedRequiredVariables.addAll(expandVariablesUsingBeforeDependencies(newVars, beforeMethodDependencies));
                        }
                    }
                }
                // Handle variable declarations
                else if (expr.isVariableDeclarationExpr()) {
                    VariableDeclarationExpr varDeclExpr = expr.asVariableDeclarationExpr();

                    // Check each variable in the declaration
                    for (VariableDeclarator var : varDeclExpr.getVariables()) {
                        String varName = var.getNameAsString();

                        // Keep if it defines a required variable
                        if (expandedRequiredVariables.contains(varName)) {
                            keepStatement = true;
                            var.getInitializer().ifPresent(initializer -> {
                                Set<String> newVars = getVariablesUsedInExpression(initializer);
                                expandedRequiredVariables.addAll(newVars);
                                expandedRequiredVariables.addAll(
                                        expandVariablesUsingBeforeDependencies(newVars, beforeMethodDependencies)
                                );
                            });
                        }
                        // Or if any method within is relevant
                        else {
                            boolean anyMethodRelevant = var.getInitializer()
                                    .map(initializer -> initializer.findAll(MethodCallExpr.class).stream()
                                            .anyMatch(methodCallExpr -> {
                                                if (checkMethodExpressionsAndRequiredObjects(methodCallExpr, requiredObjectMethodCalls)) {
                                                    return true;
                                                }
                                                // Check if method is on a control object or has control objects as arguments
                                                if (methodCallExpr.getScope().isPresent()) {
                                                    String scope = methodCallExpr.getScope().get().toString();
                                                    return controlObjects.contains(scope) ||
                                                            methodCallExpr.getArguments().stream().anyMatch(arg ->
                                                                    getVariablesUsedInExpression(arg).stream().anyMatch(controlObjects::contains));
                                                }
                                                return false;
                                            }))
                                    .orElse(false);

                            if (anyMethodRelevant) {
                                keepStatement = true;
                                var.getInitializer().ifPresent(initializer -> {
                                    Set<String> newVars = getVariablesUsedInExpression(initializer);
                                    expandedRequiredVariables.addAll(newVars);
                                    expandedRequiredVariables.addAll(
                                            expandVariablesUsingBeforeDependencies(newVars, beforeMethodDependencies)
                                    );
                                });
                            }
                        }
                    }
                }
            }

            // Keep the statement if flagged, otherwise remove it
            if (keepStatement) {
                statementsToKeep.add(stmt);
            } else {
                stmt.remove();
            }
        }
    }

    private static boolean checkIfMethodArgumentsHaveRequiredObjects(
            MethodCallExpr methodCallExpr,
            Set<String> expandedRequiredVariables,
            Map<String, Set<String>> beforeMethodDependencies
    ) {

        for(int j = 0; j < methodCallExpr.getArguments().size(); j++) {
            Expression arg = methodCallExpr.getArgument(j);
            Set<String> argVars = getVariablesUsedInExpression(arg);
            if (argVars.stream().anyMatch(expandedRequiredVariables::contains)) {
                expandedRequiredVariables.addAll(argVars);
                expandedRequiredVariables.addAll(expandVariablesUsingBeforeDependencies(argVars, beforeMethodDependencies));
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
        expandedRequiredVariables.addAll(requiredObjectMethodCalls);

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
                    } else if(checkIfMethodArgumentsHaveRequiredObjects(methodCallExpr, expandedRequiredVariables, beforeMethodDependencies)) {
                        continue;
                    } else if (checkMethodExpressionsAndRequiredObjects(methodCallExpr, expandedRequiredVariables)) {
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
                            .anyMatch(methodCallExpr -> checkMethodExpressionsAndRequiredObjects(methodCallExpr, expandedRequiredVariables));

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
                                        .anyMatch(methodCallExpr -> checkMethodExpressionsAndRequiredObjects(methodCallExpr, expandedRequiredVariables)))
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

    // Helper method to get all variables used in an assertion
//    static Set<String> getVariablesUsedInAssertion(MethodCallExpr assertion) {
//        Set<String> variables = new HashSet<>();
//        assertion.getArguments().forEach(arg -> variables.addAll(getVariablesUsedInExpression(arg)));
//        return variables;
//    }

    static Set<String> getVariablesUsedInAssertion(MethodCallExpr assertion) {
        Set<String> variables = new HashSet<>();
        assertion.getArguments().forEach(arg -> variables.addAll(getVariablesUsedInExpression(arg)));

        // Also include scope of the assertion itself (e.g., isAvailable part)
        assertion.getScope().ifPresent(scope -> variables.addAll(getVariablesUsedInExpression(scope)));

        return variables;
    }

    // Helper method to recursively get all variables used in an expression
    private static Set<String> getVariablesUsedInExpression(Expression expression) {
        Set<String> variables = new HashSet<>();
        if (expression == null) return variables;

        expression.walk(NameExpr.class, nameExpr -> variables.add(nameExpr.getNameAsString()));
        return variables;
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

    private static Map<String, Set<String>> extractBeforeMethodDependencies(ClassOrInterfaceDeclaration testClass) {
        // Map to store variable dependencies
        // Key: Variable name
        // Value: Set of variables that depend on this variable
        Map<String, Set<String>> variableDependencies = new HashMap<>();

        List<MethodDeclaration> beforeMethods = testClass.getMethods().stream()
                .filter(method -> method.getAnnotationByName("Before").isPresent()
                        || method.getAnnotationByName("BeforeEach").isPresent())
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
            // Check for Thread.sleep calls
            for (MethodCallExpr methodCall : method.findAll(MethodCallExpr.class)) {
                if (methodCall.getNameAsString().equals("sleep")) {
                    // Check if it's specifically Thread.sleep
                    if (methodCall.getScope().isPresent() &&
                            methodCall.getScope().get().toString().equals("Thread")) {
                        return true;
                    }
                }
            }

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

            // Check for array initializers in method calls
            for (ArrayInitializerExpr arrayInit : method.findAll(ArrayInitializerExpr.class)) {
                return true; // Found array initializer expression
            }

            // Check for array creation expressions
            for (ArrayCreationExpr arrayCreation : method.findAll(ArrayCreationExpr.class)) {
                return true; // Found explicit array creation like 'new URI[]'
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

    // Helper method to check if a method call is part of an assertion chain
    private static boolean isAssertionMethodChain(MethodCallExpr methodCall) {
        // Check if this method starts with "assert"
        if (methodCall.getNameAsString().startsWith("assert")) {
            return true;
        }

        // Check if the scope (parent method call) is an assertion
        if (methodCall.getScope().isPresent() && methodCall.getScope().get() instanceof MethodCallExpr) {
            return isAssertionMethodChain((MethodCallExpr) methodCall.getScope().get());
        }

        return false;
    }

    public static List<MethodCallExpr> extractAssertions(MethodDeclaration testMethod) {
        List<Statement> assertionStatements = testMethod.findAll(ExpressionStmt.class)
                .stream()
                .filter(stmt -> {
                    Expression expr = stmt.getExpression();
                    // Look for method call expressions that either:
                    // 1. Start with "assert" directly (like assertEquals())
                    // 2. Are part of a method chain that starts with "assert" (like assertThat().isFalse())
                    if (expr instanceof MethodCallExpr) {
                        MethodCallExpr methodCall = (MethodCallExpr) expr;
                        return isAssertionMethodChain(methodCall);
                    }
                    return false;
                })
                .collect(Collectors.toList());
        List<MethodCallExpr> assertions = assertionStatements.stream()
                .map(stmt -> (ExpressionStmt) stmt)
                .map(ExpressionStmt::getExpression)
                .map(expr -> (MethodCallExpr) expr)
                .collect(Collectors.toList());
        return assertions;
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
        AtomicInteger totalLocOfObservedTests = new AtomicInteger();;

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

                    totalLocOfObservedTests.addAndGet(extractTestLogicLineCount(inputCompilationUnit, testMethod.getNameAsString()));

                    AtomicInteger counter = new AtomicInteger(1);
                    List<MethodDeclaration> purifiedTestsOfOriginalTest = new ArrayList<>();
                    // Collect all assert statements for backward slicing
//                    List<MethodCallExpr> assertions = testMethod.findAll(MethodCallExpr.class)
//                            .stream()
//                            .filter(call -> call.getNameAsString().startsWith("assert"))
//                            .collect(Collectors.toList());
                    List<MethodCallExpr> assertions = extractAssertions(testMethod);

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
                        result.independentLogicsInTest.put(testMethod.getNameAsString(), separableComponents);
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
        result.totalLocInObservedTests = totalLocOfObservedTests.get();

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

    public static String getLastFolderName(String repositoryPath) {
        Path path = Paths.get(repositoryPath);
        return path.getFileName().toString(); // Extracts the last folder name
    }

    private static void generateReportAssertionPasta(List<TestFileResult> results, String repositoryPath) {
        try {
            String reportPath = Paths.get("/Users/monilnarang/Documents/Research Evaluations/1May", getLastFolderName(repositoryPath) + ".md").toString();
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

                for (TestFileResult result : results) {
                    for (int components : result.independentLogicsInTest.values()) {
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
                            result.independentLogicsInTest.forEach((methodName, components) -> {
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

    private static boolean isChildOfTarget(Node node, Node targetNode) {
        Node parent = node.getParentNode().orElse(null);
        while (parent != null) {
            if (parent.equals(targetNode)) {
                return true;
            }
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }
    private static boolean anyChildIsAssert(MethodCallExpr call) {
        for(Node child : call.getChildNodes()) {
            if (child instanceof MethodCallExpr) {
                MethodCallExpr childCall = (MethodCallExpr) child;
                if (childCall.getNameAsString().startsWith("assert")) {
                    return true;
                }
            }
        }
        return false;
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
//                    List<MethodCallExpr> assertions = testMethod.findAll(MethodCallExpr.class)
//                            .stream()
//                            .filter(call -> call.getNameAsString().startsWith("assert"))
//                            .collect(Collectors.toList());
                    List<MethodCallExpr> assertions = extractAssertions(testMethod);

                    HashMap<String, NodeList<Node>> statementNodesListMap = new HashMap<>();

                    // Generate a separate test method for each assertion
                    assertions.forEach(assertStatement -> {
                        // Clone the original method to create an purified version
                        MethodDeclaration purifiedMethod = testMethod.clone();
                        String methodName = testMethod.getNameAsString() + "_" + counter.getAndIncrement();
                        purifiedMethod.setName(methodName);

                        // Remove all assertions except the current one
                        // First, handle direct MethodCallExpr assertions
                        purifiedMethod.findAll(MethodCallExpr.class).forEach(call -> {
                            if(call.getNameAsString().startsWith("assert") || anyChildIsAssert(call)) {
                                if(!call.equals(assertStatement)){
                                    call.getParentNode().ifPresent(Node::remove);
                                }
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
                // print group
                System.out.println("Potential PUTs Group: " + count + ": " + group.size() + " tests");
                for(int i=0;i<group.size();i++)
                    System.out.println(group.get(i).Name + " ");
                count++;
            }
        }
        return count;
    }

    static class ResultCreateRefreshedTestFilesInSandbox {
        int totalNewSeparatedTestsCreated;
        int totalNewPUTsCreated;
        int totalPotentialPuts;
        int totalTestsAfterP2;
        TestFileResult aggregatedResult;

        public ResultCreateRefreshedTestFilesInSandbox(int totalNewSeparatedTestsCreated, int totalNewPUTsCreated, int totalPotentialPuts, int totalTestsAfterP2, TestFileResult aggregatedResult) {
            this.totalNewSeparatedTestsCreated = totalNewSeparatedTestsCreated;
            this.totalNewPUTsCreated = totalNewPUTsCreated;
            this.totalPotentialPuts = totalPotentialPuts;
            this.totalTestsAfterP2 = totalTestsAfterP2;
            this.aggregatedResult = aggregatedResult;
        }
    }

    public static int extractTestLogicLineCount(CompilationUnit cu, String testName) {
        // Find the method with the given test name
        final int[] lineCount = {0};

        // Visit all methods in the compilation unit
        cu.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals(testName))
                .filter(method -> method.getAnnotationByName("Test").isPresent() && !method.getAnnotationByName("ParameterizedTest").isPresent())
                .forEach(method -> {
                    // Get the method body
                    Optional<BlockStmt> bodyOpt = method.getBody();
                    if (bodyOpt.isPresent()) {
                        BlockStmt body = bodyOpt.get();

                        // Get the statements in the method body
                        NodeList<Statement> statements = body.getStatements();

                        // Count the lines of code (excluding empty lines, comments, and brackets)
                        for (Statement stmt : statements) {
                            // Skip empty statements
                            if (stmt.isEmptyStmt()) {
                                continue;
                            }

                            // Convert statement to string and get its lines
                            String stmtStr = stmt.toString();
                            String[] lines = stmtStr.split("\n");

                            // Process each line
                            for (String line : lines) {
                                line = line.trim();

                                // Skip empty lines
                                if (line.isEmpty()) {
                                    continue;
                                }

                                // Skip lines with only opening or closing brackets
                                if (line.equals("{") || line.equals("}")) {
                                    continue;
                                }

                                // Skip comment lines
                                if (line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) {
                                    continue;
                                }

                                // Count this as a valid logic line
                                lineCount[0]++;
                            }
                        }
                    }
                });

        return lineCount[0];
    }

    public static String extractClassName(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        // Find the last '/' or '\' character
        int lastSlashIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));

        // Extract the filename with extension
        String fileNameWithExtension = path.substring(lastSlashIndex + 1);

        // Remove the .java extension if it exists
        if (fileNameWithExtension.endsWith(".java")) {
            return fileNameWithExtension.substring(0, fileNameWithExtension.length() - 5);
        }

        return fileNameWithExtension;
    }

    public static int extractTestLogicLineCountForSplittedTests(CompilationUnit cu, String testName) {
        // Create a prefix to search for in method names
        String methodPrefix = testName + "_";

        // Track the total line count across all matching methods
        final int[] totalLineCount = {0};

        // Visit all methods in the compilation unit
        cu.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().startsWith(methodPrefix))
                .forEach(method -> {
                    // Get the method body
                    Optional<BlockStmt> bodyOpt = method.getBody();
                    if (bodyOpt.isPresent()) {
                        BlockStmt body = bodyOpt.get();

                        // Get the statements in the method body
                        NodeList<Statement> statements = body.getStatements();

                        // Count the lines of code (excluding empty lines, comments, and brackets)
                        for (Statement stmt : statements) {
                            // Skip empty statements
                            if (stmt.isEmptyStmt()) {
                                continue;
                            }

                            // Convert statement to string and get its lines
                            String stmtStr = stmt.toString();
                            String[] lines = stmtStr.split("\n");

                            // Process each line
                            for (String line : lines) {
                                line = line.trim();

                                // Skip empty lines
                                if (line.isEmpty()) {
                                    continue;
                                }

                                // Skip lines with only opening or closing brackets
                                if (line.equals("{") || line.equals("}")) {
                                    continue;
                                }

                                // Skip comment lines
                                if (line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) {
                                    continue;
                                }

                                // Count this as a valid logic line
                                totalLineCount[0]++;
                            }
                        }
                    }
                });

        return totalLineCount[0];
    }

    public static int extractDisjointAssertionCountForTest(CompilationUnit cu, String testName) {
        // Create a prefix to search for in method names
        String methodPrefix = testName + "_";

        // Track the total line count across all matching methods
        final int[] totalDisjointAssertions = {0};

        // Visit all methods in the compilation unit
        cu.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().startsWith(methodPrefix))
                .forEach(method -> {
                    totalDisjointAssertions[0]++;
                });

        return totalDisjointAssertions[0];
    }

    public static List<String> extractPotentialPUTsOpportunity(List<List<UnitTest>> similarTestGroups) {
        List<String> listOppos = new ArrayList<>();
        for (List<UnitTest> group : similarTestGroups) {
            if (group.size() > 1) {
                for(int i=0;i<group.size();i++)
                    listOppos.add(group.get(i).Name);
            }
        }
        return listOppos;
    }

    public static int extractAllAssertionsForOldTestName(CompilationUnit cu, String oldName) {
        String methodPrefix = oldName + "_";
        List<MethodDeclaration> newTests = new ArrayList<>();
        cu.findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().startsWith(methodPrefix))
                .forEach(method -> {
                    newTests.add(method);
                }
        );
        int totalAssertions = 0;
        for (MethodDeclaration newTest : newTests) {
            List<MethodCallExpr> assertions = extractAssertions(newTest);
            totalAssertions += assertions.size();
        }
        return totalAssertions;
    }

    public static List<String> extractSimilarTestsForGivenRetrofittedTest(List<List<UnitTest>> similarTestGroups, String test) {
        List<String> similarTests = new ArrayList<>();
        for(List<UnitTest> group : similarTestGroups) {
            for (UnitTest unitTest : group) {
                if (unitTest.Name.startsWith(test)) {
                    similarTests.addAll(group.stream().map(unitTest1 -> unitTest1.Name).collect(Collectors.toList()));
                    break;
                }
            }
        }
        return similarTests;
    }

    public static void collectTestAnalyticsBeforePhaseI(TestFileResult testClassResult) throws FileNotFoundException {
        String className = extractClassName(testClassResult.filePath);
        CompilationUnit cu = configureJavaParserAndGetCompilationUnit(testClassResult.filePath);

        for(String test : testClassResult.listPastaTests) {
            String analyticsMethodKey = className + "#" + test;
            analyticsMap.put(
                    analyticsMethodKey, new TestAnalytics(
                        className,
                        test,
                        testClassResult.independentLogicsInTest.get(test),
                        extractTestLogicLineCount(cu, test)
                    )
            );
        }

        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
        for (MethodDeclaration method : methods) {
            if (testClassResult.listPastaTests.contains(method.getNameAsString())) {
                String analyticsMethodKey = className + "#" + method.getNameAsString();
                List<MethodCallExpr> assertions = extractAssertions(method);
                analyticsMap.get(analyticsMethodKey).assertionCount = assertions.size();
            }
        }
    }


    public static boolean collectTestAnalyticsAfterPhaseIAndReturnStop(TestFileResult testClassResult, ResultCreateNewClassFileWithSplittedTests result) throws FileNotFoundException {
        String className = extractClassName(testClassResult.filePath);
        String purifiedOutputFilePath = result.newClassFilePath;
        int totalNewSeparatedTestsCreated = result.newSeparatedTests;
        CompilationUnit cu = configureJavaParserAndGetCompilationUnit(purifiedOutputFilePath);

        for(String test: testClassResult.listPastaTests) {
            String analyticsMethodKey = className + "#" + test;
            analyticsMap.get(analyticsMethodKey).lineCountAfterP1 = extractTestLogicLineCountForSplittedTests(cu, test);
            // if line count aren't equal stop its processing
            if(analyticsMap.get(analyticsMethodKey).lineCountBefore != analyticsMap.get(analyticsMethodKey).lineCountAfterP1) {
                // stop processing whole test class
                for(String removeTest: testClassResult.listPastaTests) {
                    String analyticsMethodKey2 = className + "#" + removeTest;
                    analyticsMap.get(analyticsMethodKey2).stopProcessing = true;
                }
                return true;
            }
        }
        return false;
    }

    public static void collectTestAnalyticsBeforePhaseII(TestFileResult testClassResult, List<List<UnitTest>> similarTestGroups) {
        String className = extractClassName(testClassResult.filePath);
        List<String> putsOpportunities = extractPotentialPUTsOpportunity(similarTestGroups);

        for(String test: testClassResult.listPastaTests) {
            String analyticsMethodKey = className + "#" + test;
            for(String splitTest: putsOpportunities) {
                if (splitTest.startsWith(test + "_")) {
                    analyticsMap.get(analyticsMethodKey).isRetrofittingOpportunity = true;
                    break;
                }
            }
        }
    }

    public static void collectTestAnalyticsAfterPhaseII(TestFileResult testClassResult, String putsFile, List<List<UnitTest>> similarTestGroups) throws FileNotFoundException {
        String className = extractClassName(testClassResult.filePath);
        CompilationUnit cu = configureJavaParserAndGetCompilationUnit(putsFile);

        for(String test: testClassResult.listPastaTests) {
            String analyticsMethodKey = className + "#" + test;

            if(cu.findAll(MethodDeclaration.class).stream().anyMatch(method -> method.getNameAsString().startsWith(test + "_") && method.getAnnotationByName("ParameterizedTest").isPresent())) {
                analyticsMap.get(analyticsMethodKey).becameRetrofittedTest = true;
                analyticsMap.get(analyticsMethodKey).testsRefactoredTogether = extractSimilarTestsForGivenRetrofittedTest(similarTestGroups, test + "_");
            }
            // update this to actually check -> only execute if run successful, so fine
            if(analyticsMap.get(analyticsMethodKey).isRetrofittingOpportunity)
                analyticsMap.get(analyticsMethodKey).retrofittingSuccessful = true;
            analyticsMap.get(analyticsMethodKey).lineCountAfterP2 = extractTestLogicLineCountForSplittedTests(cu, test);
            analyticsMap.get(analyticsMethodKey).assertionCountAfterP2 = extractAllAssertionsForOldTestName(cu, test);
        }
    }

    public static ResultCreateRefreshedTestFilesInSandbox createRefreshedTestFilesInSandbox(List<TestFileResult> results) throws IOException {
        int totalNewSeparatedTestsCreated = 0;
        int totalNewPUTsCreated = 0;
        int totalPotentialPuts = 0;
        int totalTestsAfterP2 = 0;
        TestFileResult aggregated = new TestFileResult();
        for (TestFileResult testClassResult : results) {
            if(testClassResult.pastaCount == 0) {
                aggregated = aggregate(aggregated, testClassResult);
                continue;
            }

            collectTestAnalyticsBeforePhaseI(testClassResult);

            // PHASE I
            // Purified file has separated tests of only the pasta tests from the original file.
            ResultCreateNewClassFileWithSplittedTests resultx = createNewClassFileWithSplittedTests(testClassResult);
            String purifiedOutputFilePath = resultx.newClassFilePath;
            boolean stop = collectTestAnalyticsAfterPhaseIAndReturnStop(testClassResult, resultx);

            if(stop) {
                System.out.println("Stopping processing for file: " + testClassResult.filePath);
                // ideally also delete the purified file
                continue;
            }

            aggregated = aggregate(aggregated, testClassResult);
            totalNewSeparatedTestsCreated += resultx.newSeparatedTests;
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
                CompilationUnit cuCut = configureJavaParserAndGetCompilationUnit(purifiedOutputFilePath);
                // check logic ? ? ?
                totalTestsAfterP2 += countTestMethods(cuCut);
                continue;
            }
            collectTestAnalyticsBeforePhaseII(testClassResult, similarTestGroups);
            List<MethodDeclaration> newPUTs = new ArrayList<>();
            try {
                newPUTs = retrofitSimilarTestsTogether(similarTestGroups, cu);
            } catch (Exception e) {
                System.out.println("Error Creating PUTs");
            }

            if(newPUTs.size() == 0) {
                System.out.println("ERROR?: Puts should be created: " + purifiedOutputFilePath);
                // todo update analytics map to have false in retrofitting successful for all tests of class
                // todo fix hadoop failure due to java parser, skip such unparsable classes
                //      => is the unparsable code something I created?
            }
            else {
                System.out.println(newPUTs.size()/2 + " new PUTs created for file: " + purifiedOutputFilePath);
                totalNewPUTsCreated = totalNewPUTsCreated + newPUTs.size()/2;
                String putsFile = createParameterizedTestFile(purifiedOutputFilePath, newPUTs, extractTestMethodsToExclude(similarTestGroups));
                collectTestAnalyticsAfterPhaseII(testClassResult, putsFile, similarTestGroups);

                CompilationUnit cuPut = configureJavaParserAndGetCompilationUnit(putsFile);
                // check logic ? ? ?
                totalTestsAfterP2 += countTestMethods(cuPut);
            }
        }
//        System.out.println("Total potential PUTs: " + totalPotentialPuts);
//        System.out.println("Total new separated tests created: " + totalNewSeparatedTestsCreated);

        // Calculate the overall percentage
        if (aggregated.totalConsideredTests > 0) {
            aggregated.pastaPercentage =
                    (double) aggregated.pastaCount / aggregated.totalConsideredTests * 100;
        }
        return new ResultCreateRefreshedTestFilesInSandbox(totalNewSeparatedTestsCreated, totalNewPUTsCreated, totalPotentialPuts, totalTestsAfterP2, aggregated);
    }

    public static int countTestMethods(CompilationUnit cu) {
        // Counter for the number of test methods
        AtomicInteger testCount = new AtomicInteger(0);

        // Visit all method declarations in the CompilationUnit
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            // Check if the method has either @Test or @ParameterizedTest annotation
            boolean isTest = method.getAnnotations().stream()
                    .anyMatch(annotation -> {
                        String name = annotation.getNameAsString();
                        return name.equals("Test") || name.equals("ParameterizedTest");
                    });

            if (isTest) {
                testCount.incrementAndGet();
            }
        });

        return testCount.get();
    }

    public static TestFileResult aggregate(TestFileResult aggregated, TestFileResult result) {

        // Sum up the integer values
        aggregated.totalTests += result.totalTests;
        aggregated.totalConsideredTests += result.totalConsideredTests;
        aggregated.pastaCount += result.pastaCount;
        aggregated.totalLocInObservedTests += result.totalLocInObservedTests;
        return aggregated;
    }

    /**
     * Processes multiple repositories to fix assertion pasta and generates a Markdown report for each.
     *
     * @param commaSeparatedPaths A string of comma-separated paths to Java repositories
     * @throws IOException If an I/O error occurs
     */
    public static void fixAssertionPastaInMultipleRepositoriesAndGenerateReports(String commaSeparatedPaths) throws IOException {
            String reportOutputDir = "/Users/monilnarang/Documents/Research Evaluations/1May";
            // Create output directory if it doesn't exist
            File outputDir = new File(reportOutputDir);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            // Split the input string by commas
            String[] paths = commaSeparatedPaths.split(",");

            // Create a StringBuilder to collect all reports
            StringBuilder allReportsBuilder = new StringBuilder();
            allReportsBuilder.append("# Assertion Pasta Fix Reports\n\n");

            // Track overall statistics
            int totalTestSplitted = 0;
            int totalPotentialPuts = 0;
            int totalNewPutsCreated = 0;

            // Process each path
            for (String path : paths) {
                String trimmedPath = path.trim();
                System.out.println("Processing repository: " + trimmedPath);

                // Create a StringBuilder for the current report
                StringBuilder reportBuilder = new StringBuilder();
                reportBuilder.append("## Repository: ").append(trimmedPath).append("\n\n");

                try {
                    // Redirect System.out temporarily to capture the output
                    PrintStream originalOut = System.out;
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    PrintStream capturedOut = new PrintStream(outputStream);
                    System.setOut(capturedOut);

                    // Call the original method
                    fixAssertionPastaInRepo(trimmedPath);

                    // Restore the original System.out
                    System.setOut(originalOut);

                    // Get the captured output
                    String capturedOutput = outputStream.toString();
                    System.out.println(capturedOutput); // Print to console as well

                    // Extract statistics from the captured output
                    String[] lines = capturedOutput.split("\n");
                    int potentialPuts = 0;
                    int newPutsCreated = 0;

                    for (String line : lines) {
                        reportBuilder.append("- ").append(line).append("\n");

                        if (line.startsWith("Total potential PUTs:")) {
                            potentialPuts = Integer.parseInt(line.substring("Total potential PUTs:".length()).trim());
                            totalPotentialPuts += potentialPuts;
                        } else if (line.startsWith("Total new PUTs created:")) {
                            newPutsCreated = Integer.parseInt(line.substring("Total new PUTs created:".length()).trim());
                            totalNewPutsCreated += newPutsCreated;
                        } else if (line.startsWith("Total new separated tests created:")) {
                            totalTestSplitted += Integer.parseInt(line.substring("Total new separated tests created:".length()).trim());
                        }
                    }

                    // Calculate PUT conversion percentage
                    double putConversionPercentage = potentialPuts > 0 ?
                            ((double) newPutsCreated / potentialPuts) * 100 : 0;

                    reportBuilder.append("- PUT Conversion Rate: ")
                            .append(String.format("%.2f%%", putConversionPercentage))
                            .append("\n\n");
                } catch (Exception e) {
                    reportBuilder.append("### Error\n");
                    reportBuilder.append("- Error processing repository: ").append(e.getMessage()).append("\n\n");
                    System.err.println("Error processing repository " + trimmedPath + ": " + e.getMessage());
                    e.printStackTrace();
                }

                // Write the individual report to a file
                String safeFileName = trimmedPath.replaceAll("[^a-zA-Z0-9.-]", "_");
                String reportFileName = outputDir.getPath() + File.separator + "assertion_pasta_report_" + safeFileName + ".md";
                try (FileWriter writer = new FileWriter(reportFileName)) {
                    writer.write(reportBuilder.toString());
                }

                System.out.println("Report generated: " + reportFileName);

                // Add this report to the overall report
                allReportsBuilder.append(reportBuilder);
            }

            // Calculate overall PUT conversion percentage
            double overallPutConversionPercentage = totalPotentialPuts > 0 ?
                    ((double) totalNewPutsCreated / totalPotentialPuts) * 100 : 0;

            allReportsBuilder.append("## Summary\n\n");
            allReportsBuilder.append("- Total tests splitted across all repositories: ").append(totalTestSplitted).append("\n");
            allReportsBuilder.append("- Total potential PUTs across all repositories: ").append(totalPotentialPuts).append("\n");
            allReportsBuilder.append("- Total new PUTs created across all repositories: ").append(totalNewPutsCreated).append("\n");
            allReportsBuilder.append("- Overall PUT Conversion Rate: ")
                    .append(String.format("%.2f%%", overallPutConversionPercentage))
                    .append("\n");

            // Write the combined report to a file
            String allReportsFileName = outputDir.getPath() + File.separator + "all_assertion_pasta_reports.md";
            try (FileWriter writer = new FileWriter(allReportsFileName)) {
                writer.write(allReportsBuilder.toString());
            }

            System.out.println("Combined report generated: " + allReportsFileName);
        }

    public static ResultCreateRefreshedTestFilesInSandbox fixAssertionPastaInRepo(String pathToJavaRepository) throws IOException {
        List<TestFileResult> results = getAssertionPastaResultsInRepo(pathToJavaRepository);
        // for each results in a file
        // create file with only pastas and todo: add all of top code
        ResultCreateRefreshedTestFilesInSandbox result = createRefreshedTestFilesInSandbox(results);
        System.out.println("Total tests: " + result.aggregatedResult.totalTests);
        System.out.println("Total considered tests: " + result.aggregatedResult.totalConsideredTests);
        System.out.println("Total pasta tests: " + result.aggregatedResult.pastaCount);
        System.out.println("Total pasta percentage: " + result.aggregatedResult.pastaPercentage);
        System.out.println("Total new separated tests created after P1: " + result.totalNewSeparatedTestsCreated);
        System.out.println("Total potential PUTs: " + result.totalPotentialPuts);
        System.out.println("Total new PUTs created: " + result.totalNewPUTsCreated);
        System.out.println("Total test count after P2: " + result.totalTestsAfterP2);
        return result;
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

    public static void exportAnalyticsToCSV(String filePath, ResultCreateRefreshedTestFilesInSandbox result) {
        try {
            // Ensure directory exists
            File directory = new File(filePath.substring(0, filePath.lastIndexOf('/')));
            if (!directory.exists()) {
                directory.mkdirs();
            }

            // Create file and writer
            FileWriter csvWriter = new FileWriter(filePath);

            // Write header
            csvWriter.append("TestClass,TestMethod,#DisjointAssertions,#LocBefore,#Assertions,");
            csvWriter.append("#LocAfterP1,IsPUTOpportunity,PUTSuccess,");
            csvWriter.append("BecamePUT,#LocAfterP2, #AssertionAfterP2, TestsPUTTogether\n");

            int entriesCount = 0;

            // Write data rows
            for (Map.Entry<String, TestAnalytics> entry : analyticsMap.entrySet()) {
                TestAnalytics analytics = entry.getValue();

                if(analytics.stopProcessing) {
                    continue;
                }
                if(analytics.lineCountBefore!=analytics.lineCountAfterP1) {
                    System.out.println("ERROR? : Found unequal lines before and after");
                    continue;
                }

                entriesCount++;

                csvWriter.append(analytics.testClassName).append(",");
                csvWriter.append(analytics.testMethodName).append(",");
                csvWriter.append(String.valueOf(analytics.disjointAssertionsCount)).append(",");
                csvWriter.append(String.valueOf(analytics.lineCountBefore)).append(",");
                csvWriter.append(String.valueOf(analytics.assertionCount)).append(",");
                csvWriter.append(String.valueOf(analytics.lineCountAfterP1)).append(",");
                csvWriter.append(String.valueOf(analytics.isRetrofittingOpportunity)).append(",");
                csvWriter.append(String.valueOf(analytics.retrofittingSuccessful)).append(",");
                csvWriter.append(String.valueOf(analytics.becameRetrofittedTest)).append(",");
                csvWriter.append(String.valueOf(analytics.lineCountAfterP2)).append(",");
                csvWriter.append(String.valueOf(analytics.assertionCountAfterP2)).append(",");
                csvWriter.append(String.valueOf(analytics.testsRefactoredTogether)).append("\n");
            }

            // Add a blank line to separate the sections
            csvWriter.append("\n");

            // Add summary data from ResultCreateRefreshedTestFilesInSandbox
            csvWriter.append("Summary Statistics\n");
//            csvWriter.append("Total tests,").append(String.valueOf(result.aggregatedResult.totalTests)).append("\n");
            csvWriter.append("Total considered tests,").append(String.valueOf(result.aggregatedResult.totalConsideredTests)).append("\n");
            csvWriter.append("Total pasta tests,").append(String.valueOf(result.aggregatedResult.pastaCount)).append("\n");
            csvWriter.append("Total pasta percentage,").append(String.valueOf(result.aggregatedResult.pastaPercentage)).append("\n");
            csvWriter.append("Total new separated tests created after P1,").append(String.valueOf(result.totalNewSeparatedTestsCreated)).append("\n");
            csvWriter.append("Total potential PUTs,").append(String.valueOf(result.totalPotentialPuts)).append("\n");
            csvWriter.append("Total new PUTs created,").append(String.valueOf(result.totalNewPUTsCreated)).append("\n");
            csvWriter.append("Total test method after P2,").append(String.valueOf(result.totalTestsAfterP2)).append("\n");

            // Calculate and add the new metrics
            // 1. PUTs %
            double putsPercentage = 0;
            if (result.totalPotentialPuts > 0) {
                putsPercentage = ((double) result.totalNewPUTsCreated / result.totalPotentialPuts) * 100;
            }
            csvWriter.append("PUTs %,").append(String.valueOf(putsPercentage)).append("\n");

            // 2. Loc Before formula
            // This creates a formula that sums D2:D[entriesCount+1]
            csvWriter.append("Loc Before,=SUM(D2:D").append(String.valueOf(entriesCount + 1)).append(")\n");

            // 3. Loc After formula
            // This creates a formula that applies the array formula to the rows from 2 to entriesCount+1
            String locAfterFormula = "\"=SUM(ARRAYFORMULA(IF(H2:H" + (entriesCount + 1) + "=TRUE, J2:J" + (entriesCount + 1) + ", F2:F" + (entriesCount + 1) + ")))\"";
            csvWriter.append("Loc After,").append(locAfterFormula).append("\n");


            // Add another blank line
            csvWriter.append("\n");

            // Close the writer
            csvWriter.flush();
            csvWriter.close();

            System.out.println("CSV file created successfully at: " + filePath);

        } catch (IOException e) {
            System.err.println("Error writing CSV file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void exportAnalyticsToCSVOld(String filePath) {
        try {
            // Ensure directory exists
            File directory = new File(filePath.substring(0, filePath.lastIndexOf('/')));
            if (!directory.exists()) {
                directory.mkdirs();
            }

            // Create file and writer
            FileWriter csvWriter = new FileWriter(filePath);

            // Write header
            csvWriter.append("TestClass,TestMethod,#DisjointAssertions,#LocBefore,#Assertions,");
            csvWriter.append("#LocAfterP1,IsPUTOpportunity,PUTSuccess,");
            csvWriter.append("BecamePUT,#LocAfterP2, #AssertionAfterP2, TestsPUTTogether\n");

            // Write data rows
            for (Map.Entry<String, TestAnalytics> entry : analyticsMap.entrySet()) {
                TestAnalytics analytics = entry.getValue();

                csvWriter.append(analytics.testClassName).append(",");
                csvWriter.append(analytics.testMethodName).append(",");
                csvWriter.append(String.valueOf(analytics.disjointAssertionsCount)).append(",");
                csvWriter.append(String.valueOf(analytics.lineCountBefore)).append(",");
                csvWriter.append(String.valueOf(analytics.assertionCount)).append(",");
                csvWriter.append(String.valueOf(analytics.lineCountAfterP1)).append(",");
                csvWriter.append(String.valueOf(analytics.isRetrofittingOpportunity)).append(",");
                csvWriter.append(String.valueOf(analytics.retrofittingSuccessful)).append(",");
                csvWriter.append(String.valueOf(analytics.becameRetrofittedTest)).append(",");
                csvWriter.append(String.valueOf(analytics.lineCountAfterP2)).append(",");
                csvWriter.append(String.valueOf(analytics.assertionCountAfterP2)).append(",");
                csvWriter.append(String.valueOf(analytics.testsRefactoredTogether)).append("\n");
            }

            // Close the writer
            csvWriter.flush();
            csvWriter.close();

            System.out.println("CSV file created successfully at: " + filePath);

        } catch (IOException e) {
            System.err.println("Error writing CSV file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String extractRepoNameFromPath(String inputPath) {
        // Handle null or empty path
        if (inputPath == null || inputPath.isEmpty()) {
            return "unknown_repo";
        }

        // Normalize path separators to handle both Windows and Unix paths
        String normalizedPath = inputPath.replace('\\', '/');

        // Remove trailing slash if present
        if (normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }

        // Extract the last folder name from the path
        int lastSlashIndex = normalizedPath.lastIndexOf('/');
        if (lastSlashIndex >= 0 && lastSlashIndex < normalizedPath.length() - 1) {
            String folderName = normalizedPath.substring(lastSlashIndex + 1);

            // If it's a file, remove the file extension
            int dotIndex = folderName.lastIndexOf('.');
            if (dotIndex > 0) {
                folderName = folderName.substring(0, dotIndex);
            }

            return folderName;
        }

        // If we couldn't extract a name, return a default
        return "repo_analytics";
    }

    private static void updateReportSheet(Workbook workbook, String sheetName, ResultCreateRefreshedTestFilesInSandbox result, int entriesCount, double averageValueSetsInPUTs) {
        // Get or create the Report sheet
        Sheet reportSheet = workbook.getSheet("Report");
        if (reportSheet == null) {
            reportSheet = workbook.createSheet("Report");

            // Create header row for new report sheet
            Row headerRow = reportSheet.createRow(0);
            String[] reportHeaders = {
                    "Repo","Total Tests Considered", "Total Pasta Tests", "% Pasta",
                    "LOC Before", "LOC After", "New PUTs Created", "% PUTs", "Average Value Sets in PUTs", "Total Test", "LocTotalObserved"
            };

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            for (int i = 0; i < reportHeaders.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(reportHeaders[i]);
                cell.setCellStyle(headerStyle);
            }
        }

        // Find if this repo already exists in the report
        int targetRow = -1;
        for (int i = 1; i <= reportSheet.getLastRowNum(); i++) {
            Row row = reportSheet.getRow(i);
            if (row != null && row.getCell(0) != null &&
                    row.getCell(0).getStringCellValue().equals(sheetName)) {
                targetRow = i;
                break;
            }
        }

        // If repo not found, create new row
        if (targetRow == -1) {
            targetRow = reportSheet.getLastRowNum() + 1;
        }

        Row dataRow = reportSheet.createRow(targetRow);

        // Calculate PUTs percentage
        double putsPercentage = 0;
        if (result.totalPotentialPuts > 0) {
            putsPercentage = ((double) result.totalNewPUTsCreated / result.totalPotentialPuts) * 100;
        }

        // Populate the row with data
        dataRow.createCell(0).setCellValue(sheetName); // Repo name
        dataRow.createCell(1).setCellValue(result.aggregatedResult.totalConsideredTests); // Total tests considered
        dataRow.createCell(2).setCellValue(result.aggregatedResult.pastaCount); // Total pasta tests
        dataRow.createCell(3).setCellValue(result.aggregatedResult.pastaPercentage); // % Pasta

        // LOC Before - create formula reference to the main sheet
        Cell locBeforeCell = dataRow.createCell(4);
        locBeforeCell.setCellFormula("'" + sheetName + "'!B" + (entriesCount + 12)); // Adjust row reference based on your layout

        // LOC After - create formula reference to the main sheet
        Cell locAfterCell = dataRow.createCell(5);
        locAfterCell.setCellFormula("'" + sheetName + "'!B" + (entriesCount + 13)); // Adjust row reference based on your layout

        dataRow.createCell(6).setCellValue(result.totalNewPUTsCreated); // New PUTs created
        dataRow.createCell(7).setCellValue(putsPercentage); // % PUTs
        dataRow.createCell(8).setCellValue(averageValueSetsInPUTs); // % PUTs
        dataRow.createCell(9).setCellValue(result.aggregatedResult.totalTests); // Total tests in repo
        dataRow.createCell(10).setCellValue(result.aggregatedResult.totalLocInObservedTests); // Total tests in repo

        // Auto-size columns
        for (int i = 0; i < 8; i++) {
            reportSheet.autoSizeColumn(i);
        }
    }

    public static void exportAnalyticsToXLSX(String filePath, ResultCreateRefreshedTestFilesInSandbox result, String sheetName) {
        try {
            // Create or load the workbook
            Workbook workbook;
            File file = new File(filePath);

            // Ensure directory exists
            File directory = new File(filePath.substring(0, filePath.lastIndexOf('/')));
            if (!directory.exists()) {
                directory.mkdirs();
            }

            // Check if file exists to either create new or update existing
            if (file.exists()) {
                FileInputStream fis = new FileInputStream(file);
                workbook = new XSSFWorkbook(fis);
                fis.close();
            } else {
                workbook = new XSSFWorkbook();
            }

            // Create a new sheet or get existing sheet with that name
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet != null) {
                // Remove existing sheet if it exists to replace it
                workbook.removeSheetAt(workbook.getSheetIndex(sheet));
            }
            sheet = workbook.createSheet(sheetName);

            // Create cell style for headers
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            // Create header row
            Row headerRow = sheet.createRow(0);
            String[] headers = {
                    "TestClass", "TestMethod", "#DisjointAssertions", "#LocBefore", "#Assertions",
                    "#LocAfterP1", "IsPUTOpportunity", "PUTSuccess", "BecamePUT", "#LocAfterP2",
                    "#AssertionAfterP2", "TestsPUTTogether"
            };

            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // Keep track of row index and entries count
            int rowIdx = 1;
            int entriesCount = 0;

            // Write data rows
            for (Map.Entry<String, TestAnalytics> entry : analyticsMap.entrySet()) {
                TestAnalytics analytics = entry.getValue();

                if (analytics.stopProcessing) {
                    continue;
                }
                if (analytics.lineCountBefore != analytics.lineCountAfterP1) {
                    System.out.println("ERROR? : Found unequal lines before and after");
                    continue;
                }

                entriesCount++;
                Row row = sheet.createRow(rowIdx++);

                row.createCell(0).setCellValue(analytics.testClassName);
                row.createCell(1).setCellValue(analytics.testMethodName);
                row.createCell(2).setCellValue(analytics.disjointAssertionsCount);
                row.createCell(3).setCellValue(analytics.lineCountBefore);
                row.createCell(4).setCellValue(analytics.assertionCount);
                row.createCell(5).setCellValue(analytics.lineCountAfterP1);
                row.createCell(6).setCellValue(analytics.isRetrofittingOpportunity);
                row.createCell(7).setCellValue(analytics.retrofittingSuccessful);
                row.createCell(8).setCellValue(analytics.becameRetrofittedTest);
                row.createCell(9).setCellValue(analytics.lineCountAfterP2);
                row.createCell(10).setCellValue(analytics.assertionCountAfterP2);
                row.createCell(11).setCellValue(String.valueOf(analytics.testsRefactoredTogether));
            }

            // Add a blank row
            rowIdx++;

            // Add summary statistics
            Row titleRow = sheet.createRow(rowIdx++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("Summary Statistics");
            titleCell.setCellStyle(headerStyle);

            // calculate average value sets per PUT
            double averageValueSetsPerPUT = 0;
            int totalValueSets = 0;
            for(TestAnalytics analytics : analyticsMap.values()) {
                if (analytics.testsRefactoredTogether != null && analytics.testsRefactoredTogether.size() > 0) {
                    totalValueSets = totalValueSets + analytics.testsRefactoredTogether.size();
                }
            }
            averageValueSetsPerPUT = totalValueSets / (double) result.totalNewPUTsCreated;
            if(result.totalNewPUTsCreated == 0) {
                averageValueSetsPerPUT = 0; // Avoid division by zero
            }

            // Add summary data rows
            addSummaryRow(sheet, rowIdx++, "Total considered tests", result.aggregatedResult.totalConsideredTests);
            addSummaryRow(sheet, rowIdx++, "Total pasta tests", result.aggregatedResult.pastaCount);
            addSummaryRow(sheet, rowIdx++, "Total pasta percentage", result.aggregatedResult.pastaPercentage);
            addSummaryRow(sheet, rowIdx++, "Total new separated tests created after P1", result.totalNewSeparatedTestsCreated);
            addSummaryRow(sheet, rowIdx++, "Total potential PUTs", result.totalPotentialPuts);
            addSummaryRow(sheet, rowIdx++, "Total new PUTs created", result.totalNewPUTsCreated);
            addSummaryRow(sheet, rowIdx++, "Total test method after P2", result.totalTestsAfterP2);

            // Calculate PUTs percentage
            double putsPercentage = 0;
            if (result.totalPotentialPuts > 0) {
                putsPercentage = ((double) result.totalNewPUTsCreated / result.totalPotentialPuts) * 100;
            }
            addSummaryRow(sheet, rowIdx++, "PUTs %", putsPercentage);

            // Add formula for Loc Before
            Row locBeforeRow = sheet.createRow(rowIdx++);
            locBeforeRow.createCell(0).setCellValue("Loc Before");
            Cell locBeforeCell = locBeforeRow.createCell(1);
            locBeforeCell.setCellFormula("SUM(D2:D" + (entriesCount + 1) + ")");

            // Add formula for Loc After
            Row locAfterRow = sheet.createRow(rowIdx++);
            locAfterRow.createCell(0).setCellValue("Loc After");
            Cell locAfterCell = locAfterRow.createCell(1);
            locAfterCell.setCellFormula("SUM(ARRAYFORMULA(IF(H2:H" + (entriesCount + 1) + "=TRUE,J2:J" + (entriesCount + 1) + ",F2:F" + (entriesCount + 1) + ")))");

            // Auto-size columns for better readability
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            addSummaryRow(sheet, rowIdx++, "Average Value sets per PUT", averageValueSetsPerPUT);
            addSummaryRow(sheet, rowIdx++, "Total Test in repo", result.aggregatedResult.totalTests);
            addSummaryRow(sheet, rowIdx++, "Total Loc in observed Tests", result.aggregatedResult.totalLocInObservedTests);

            updateReportSheet(workbook, sheetName, result, entriesCount, averageValueSetsPerPUT);

            // Write the workbook to file
            FileOutputStream fileOut = new FileOutputStream(filePath);
            workbook.write(fileOut);
            fileOut.close();
            workbook.close();

            System.out.println("Sheet '" + sheetName + "' created/updated successfully in: " + filePath);

        } catch (IOException e) {
            System.err.println("Error writing XLSX file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Helper method to add summary rows
    private static void addSummaryRow(Sheet sheet, int rowIndex, String label, Object value) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(label);

        Cell valueCell = row.createCell(1);
        if (value instanceof Integer) {
            valueCell.setCellValue((Integer) value);
        } else if (value instanceof Double) {
            valueCell.setCellValue((Double) value);
        } else if (value instanceof String) {
            valueCell.setCellValue((String) value);
        } else if (value instanceof Boolean) {
            valueCell.setCellValue((Boolean) value);
        }
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
            // print map Map<Integer, Integer> separableComponentFrequency = new HashMap<>();
             for (Map.Entry<Integer, Integer> entry : separableComponentFrequency.entrySet()) {
                System.out.println("Key: " + entry.getKey() + ", Value: " + entry.getValue());
             }
        }
        else if (operation.equals("allReposFix")) {
            fixAssertionPastaInMultipleRepositoriesAndGenerateReports(inputFile);
        }
        else if (operation.equals("detectin")) {
            identifyAssertionPastas(inputFile);
        }
        else if (operation.equals("fixInRepo")) {
            ResultCreateRefreshedTestFilesInSandbox result = fixAssertionPastaInRepo(inputFile);
            String repoName = extractRepoNameFromPath(inputFile);
            String outputFilePath = "/Users/monilnarang/Documents/Research Evaluations/analytics/Apr22/analysis.xlsx";
//            exportAnalyticsToCSV(outputFilePath, result);
            exportAnalyticsToXLSX(outputFilePath, result, repoName);
        } else if(operation.equals("fixInAllRepoWithXLSXReport")) {
            String[] inputFiles = inputFile.split(","); // Split the comma-separated paths
            for (String file : inputFiles) {
                ResultCreateRefreshedTestFilesInSandbox result = fixAssertionPastaInRepo(file);
                String repoName = extractRepoNameFromPath(file);
                String outputFilePath = "/Users/monilnarang/Documents/Research Evaluations/analytics/Apr22/analysis.xlsx";
                exportAnalyticsToXLSX(outputFilePath, result, repoName);
                analyticsMap.clear();
                separableComponentFrequency.clear();
                TotalRedundantTests = 0;
                TotalNewPuts = 0;
            }
        }
        else if(operation.equals("fixinfile")) {
            List<TestFileResult> clutters = new ArrayList<>();
            clutters.add(identifyAssertionPastas(inputFile));
            ResultCreateRefreshedTestFilesInSandbox result = createRefreshedTestFilesInSandbox(clutters);
            System.out.println("Total new separated tests created: " + result.totalNewSeparatedTestsCreated);
            System.out.println("Total potential PUTs: " + result.totalPotentialPuts);
            System.out.println("Total new PUTs created: " + result.totalNewPUTsCreated);
        } else if (operation.equals("fixin")) {
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
            TestFileResult result = identifyAssertionPastas(inputFile);
            createRefreshedTestFilesInSandbox(Collections.singletonList(result));
//            String purifiedTestsFile = createPurifiedTestFile(inputFile);
//            System.out.println( "Purified test file created: " + purifiedTestsFile);
//
//            CompilationUnit cu = configureJavaParserAndGetCompilationUnit(purifiedTestsFile);
//            // Later Quality check -> [runnable] [same coverage]
//            List<String> listTestMethods = extractTestMethodListFromCU(cu);
//            HashMap<String, NodeList<Node>> statementNodesListMap = extractASTNodesForTestMethods(cu, listTestMethods);
//            List<List<UnitTest>> similarTestGroups = groupSimilarTests(listTestMethods, statementNodesListMap);
//            // ToDo : Impl logic to merge similar tests together
//            // create map: similarTestGroups - > lists of params for each group
//
//            List<MethodDeclaration> newPUTs = retrofitSimilarTestsTogether(similarTestGroups, cu);
//            String putsFile = createParameterizedTestFile(purifiedTestsFile, newPUTs, new ArrayList<>());
//            createGPTEnhancedTestFile(putsFile, newPUTs);
            // ToDo: Add logic to merge separate PUTs into a single PUT
            // ToDo: Experiment on hadoop old dataset test files
            // Later: Quality check -> [runnable] [same or more coverage]
            // Later: [handling failing tests]
        } else {
            throw new IllegalArgumentException("Invalid operation. Please provide either 'detect' or 'fix'.");
        }
    }
}


// And if after unique line count is less than before skip that test? (e.g. , Thread.sleep(1000);)







