package refactor2refresh;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.FileWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.json.JSONArray;

public class TestEnhancer {

    // Claude API constants
    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_KEY = "YOUR_API_KEY"; // Replace with your actual API key
    private static final String CLAUDE_MODEL = "claude-3-7-sonnet-20250219"; // Or your preferred model

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java TestEnhancer <input_test_file_path> <output_test_file_path>");
            return;
        }

        String inputFilePath = args[0];
        String outputFilePath = args[1];

        try {
            enhanceTestFile(inputFilePath, outputFilePath);
            System.out.println("Test enhancement completed successfully. Output file: " + outputFilePath);
        } catch (Exception e) {
            System.err.println("Error enhancing tests: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void enhanceTestFile(String inputFilePath, String outputFilePath) throws Exception {
        // Parse the input Java file
        Path path = Paths.get(inputFilePath);
        CompilationUnit cu = new JavaParser().parse(Files.readString(path)).getResult().orElseThrow();

        // Enable lexical preservation to maintain formatting
        LexicalPreservingPrinter.setup(cu);

        // Find the class
        ClassOrInterfaceDeclaration testClass = cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new RuntimeException("No class found in file"));

        // Extract the class under test (if available)
        String classUnderTest = inferClassUnderTest(testClass);

        // Collect method signatures of the methods under test (if available)
        List<String> methodUnderTestSignatures = extractMethodUnderTestSignatures(testClass);

        // Enhance parameterized tests
        enhanceParameterizedTests(cu, testClass, classUnderTest, methodUnderTestSignatures);

        // Rename test methods for better clarity
        renameTestMethods(cu, testClass, classUnderTest, methodUnderTestSignatures);

        // Write the modified compilation unit to the output file
        try (FileWriter writer = new FileWriter(outputFilePath)) {
            writer.write(LexicalPreservingPrinter.print(cu));
        }
    }

    private static String inferClassUnderTest(ClassOrInterfaceDeclaration testClass) {
        String className = testClass.getNameAsString();
        // Common patterns for test class names
        if (className.endsWith("Test")) {
            return className.substring(0, className.length() - 4);
        } else if (className.endsWith("Tests")) {
            return className.substring(0, className.length() - 5);
        } else if (className.startsWith("Test")) {
            return className.substring(4);
        }

        // If no pattern matches, try to infer from method calls or field types
        return extractClassNameFromFields(testClass);
    }

    private static String extractClassNameFromFields(ClassOrInterfaceDeclaration testClass) {
        // Try to find fields that might be the class under test
        return testClass.getFields().stream()
                .filter(f -> !f.isStatic())
                .flatMap(f -> f.getVariables().stream())
                .map(v -> v.getType().asString())
                .findFirst()
                .orElse("UnknownClass");
    }

    private static List<String> extractMethodUnderTestSignatures(ClassOrInterfaceDeclaration testClass) {
        List<String> signatures = new ArrayList<>();

        // Look for method calls in test methods that might be the methods under test
        testClass.getMethods().stream()
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Test") ||
                                a.getNameAsString().equals("ParameterizedTest")))
                .forEach(testMethod -> {
                    testMethod.findAll(MethodCallExpr.class).stream()
                            .filter(methodCall -> !methodCall.getNameAsString().startsWith("assert") &&
                                    !methodCall.getNameAsString().equals("when") &&
                                    !methodCall.getNameAsString().equals("verify"))
                            .forEach(methodCall -> {
                                if (methodCall.getScope().isPresent()) {
                                    Expression scope = methodCall.getScope().get();
                                    String methodSignature = scope + "." + methodCall.getNameAsString() +
                                            "(" + String.join(", ", methodCall.getArguments().stream()
                                            .map(Object::toString).toList()) + ")";
                                    signatures.add(methodSignature);
                                }
                            });
                });

        return signatures;
    }

    private static void enhanceParameterizedTests(CompilationUnit cu, ClassOrInterfaceDeclaration testClass,
                                                  String classUnderTest, List<String> methodSignatures) throws Exception {
        List<MethodDeclaration> parameterizedTests = testClass.getMethods().stream()
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("ParameterizedTest")))
                .toList();

        for (MethodDeclaration paramTest : parameterizedTests) {
            // Find the associated provider method
            Optional<MethodDeclaration> providerMethodOpt = testClass.getMethods().stream()
                    .filter(m -> m.getNameAsString().equals(paramTest.getNameAsString() + "Provider"))
                    .findFirst();

            if (providerMethodOpt.isPresent()) {
                MethodDeclaration providerMethod = providerMethodOpt.get();

                // 1. Add more value sets with edge cases
                enhanceProviderMethod(providerMethod, paramTest, classUnderTest, methodSignatures);

                // 2. Update parameter variable names
                enhanceParameterNames(paramTest, classUnderTest, methodSignatures);
            }
        }
    }

    private static void enhanceProviderMethod(MethodDeclaration providerMethod, MethodDeclaration paramTest,
                                              String classUnderTest, List<String> methodSignatures) throws Exception {
        // Get the current method body as string
        String methodBody = providerMethod.getBody().orElseThrow().toString();

        // Get parameter information
        List<String> paramTypes = paramTest.getParameters().stream()
                .map(p -> p.getType().asString())
                .toList();
        List<String> paramNames = paramTest.getParameters().stream()
                .map(p -> p.getNameAsString())
                .toList();

        // Prepare prompt for Claude to suggest additional edge cases
        String prompt = "I have a parameterized test provider method that currently contains these parameter sets:\n\n" +
                methodBody + "\n\n" +
                "The test method has these parameters: " +
                String.join(", ", paramTypes.stream().map((t, i) -> t + " " + paramNames.get(i))
                        .toList()) + "\n\n" +
                "The test is for " + classUnderTest +
                (methodSignatures.isEmpty() ? "" : " testing these methods:\n" +
                        String.join("\n", methodSignatures)) + "\n\n" +
                "Please suggest 3-5 additional value sets that test edge cases. " +
                "Return ONLY the new arguments() method calls without explanation, one per line, like this format:\n" +
                "arguments(value1, value2)\n" +
                "arguments(value3, value4)";

        // Call Claude API to get suggestions
        String claudeResponse = callClaudeAPI(prompt);

        // Extract the suggested arguments calls
        List<String> newArgumentCalls = extractArgumentCalls(claudeResponse);

        // Modify the provider method to include new argument calls
        if (!newArgumentCalls.isEmpty()) {
            String updatedMethodBody = addArgumentsToProviderMethod(providerMethod.toString(), newArgumentCalls);

            // Parse the updated method body and replace the original method
            MethodDeclaration updatedMethod = new JavaParser().parseMethodDeclaration(updatedMethodBody).getResult()
                    .orElseThrow();
            providerMethod.setBody(updatedMethod.getBody().orElseThrow());
        }
    }

    private static List<String> extractArgumentCalls(String claudeResponse) {
        List<String> result = new ArrayList<>();
        Pattern pattern = Pattern.compile("arguments\\s*\\(.*?\\)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(claudeResponse);

        while (matcher.find()) {
            result.add(matcher.group().trim());
        }

        return result;
    }

    private static String addArgumentsToProviderMethod(String methodText, List<String> newArgumentCalls) {
        // Find where to insert the new arguments
        int insertPosition = methodText.lastIndexOf("arguments");
        if (insertPosition == -1) {
            insertPosition = methodText.indexOf("return Stream.of(") + "return Stream.of(".length();
        } else {
            // Find the end of this arguments call
            insertPosition = methodText.indexOf(")", insertPosition) + 1;
        }

        // Insert the new arguments
        StringBuilder sb = new StringBuilder(methodText);
        for (String argCall : newArgumentCalls) {
            sb.insert(insertPosition, ",\n        " + argCall);
        }

        return sb.toString();
    }

    private static void enhanceParameterNames(MethodDeclaration paramTest, String classUnderTest,
                                              List<String> methodSignatures) throws Exception {
        // Get current parameter information
        List<String> currentParamTypes = paramTest.getParameters().stream()
                .map(p -> p.getType().asString())
                .toList();
        List<String> currentParamNames = paramTest.getParameters().stream()
                .map(p -> p.getNameAsString())
                .toList();

        // Prepare prompt for Claude to suggest better parameter names
        String prompt = "I have a parameterized test method with these parameters:\n\n" +
                String.join(", ", currentParamTypes.stream()
                        .map((t, i) -> t + " " + currentParamNames.get(i))
                        .toList()) + "\n\n" +
                "The test is for " + classUnderTest +
                (methodSignatures.isEmpty() ? "" : " testing these methods:\n" +
                        String.join("\n", methodSignatures)) + "\n\n" +
                "Please suggest more meaningful names for these parameters based on their likely purpose. " +
                "Return ONLY a comma-separated list of new parameter names without types or explanations.";

        // Call Claude API to get suggestions
        String claudeResponse = callClaudeAPI(prompt);

        // Extract the suggested parameter names
        List<String> newParamNames = extractParameterNames(claudeResponse);

        // Update the parameter names if we got valid suggestions
        if (newParamNames.size() == currentParamNames.size()) {
            for (int i = 0; i < newParamNames.size(); i++) {
                paramTest.getParameter(i).setName(newParamNames.get(i));
            }
        }
    }

    private static List<String> extractParameterNames(String claudeResponse) {
        // Remove any explanations and get just the comma-separated list
        String cleanResponse = claudeResponse.replaceAll("(?s).*?(\\w+(?:\\s*,\\s*\\w+)*).*", "$1");

        // Split by commas and trim
        return List.of(cleanResponse.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static void renameTestMethods(CompilationUnit cu, ClassOrInterfaceDeclaration testClass,
                                          String classUnderTest, List<String> methodSignatures) throws Exception {
        // Get all test methods
        List<MethodDeclaration> testMethods = testClass.getMethods().stream()
                .filter(m -> m.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Test") ||
                                a.getNameAsString().equals("ParameterizedTest")))
                .toList();

        // Batch process test methods to reduce API calls
        int batchSize = 5;
        for (int i = 0; i < testMethods.size(); i += batchSize) {
            int end = Math.min(i + batchSize, testMethods.size());
            List<MethodDeclaration> batch = testMethods.subList(i, end);

            renameTestMethodBatch(batch, classUnderTest, methodSignatures);
        }
    }

    private static void renameTestMethodBatch(List<MethodDeclaration> testMethods, String classUnderTest,
                                              List<String> methodSignatures) throws Exception {
        // Create a map of current method names
        List<String> currentNames = testMethods.stream()
                .map(MethodDeclaration::getNameAsString)
                .toList();

        // Create batch method contents for context
        StringBuilder methodContextBuilder = new StringBuilder();
        for (int i = 0; i < testMethods.size(); i++) {
            methodContextBuilder.append("Method ").append(i + 1).append(": ")
                    .append(currentNames.get(i)).append("\n")
                    .append(testMethods.get(i).toString()).append("\n\n");
        }

        // Prepare prompt for Claude
        String prompt = "I have " + testMethods.size() + " test methods for " + classUnderTest + ":\n\n" +
                methodContextBuilder.toString() + "\n" +
                (methodSignatures.isEmpty() ? "" : "The methods under test are:\n" +
                        String.join("\n", methodSignatures) + "\n\n") +
                "Please suggest more descriptive names for these test methods that clearly indicate what aspect " +
                "they're testing. Return ONLY a JSON array with the new names in order, like:\n" +
                "[\"testMethod1NewName\", \"testMethod2NewName\", ...]\n" +
                "Make sure names follow Java method naming conventions and start with 'test'.";

        // Call Claude API
        String claudeResponse = callClaudeAPI(prompt);

        // Extract the JSON array of new names
        List<String> newNames = extractJsonArray(claudeResponse);

        // Update method names if we got valid suggestions
        if (newNames.size() == testMethods.size()) {
            for (int i = 0; i < newNames.size(); i++) {
                String newName = newNames.get(i);
                MethodDeclaration method = testMethods.get(i);

                // Also update any MethodSource annotations that reference this method
                updateMethodSourceReferences(testClass, method.getNameAsString(), newName);

                // Rename the method
                method.setName(newName);

                // If this is a parameterized test, also rename its provider method
                testClass.getMethods().stream()
                        .filter(m -> m.getNameAsString().equals(currentNames.get(i) + "Provider"))
                        .findFirst()
                        .ifPresent(providerMethod -> providerMethod.setName(newName + "Provider"));
            }
        }
    }

    private static void updateMethodSourceReferences(ClassOrInterfaceDeclaration testClass,
                                                     String oldName, String newName) {
        // Find any method source annotations that reference the old method name
        testClass.getMethods().forEach(method -> {
            method.getAnnotations().forEach(annotation -> {
                if (annotation.getNameAsString().equals("MethodSource")) {
                    // Update the method name reference
                    String annotationText = annotation.toString();
                    if (annotationText.contains("\"" + oldName + "Provider\"")) {
                        String newAnnotationText = annotationText.replace(
                                "\"" + oldName + "Provider\"",
                                "\"" + newName + "Provider\""
                        );

                        // Parse and replace the annotation
                        new JavaParser().parseAnnotation(newAnnotationText).getResult().ifPresent(
                                newAnnotation -> annotation.replace(newAnnotation)
                        );
                    }
                }
            });
        });
    }

    private static List<String> extractJsonArray(String jsonString) {
        List<String> result = new ArrayList<>();

        // Extract the JSON array part
        Pattern pattern = Pattern.compile("\\[.*?\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(jsonString);

        if (matcher.find()) {
            try {
                JSONArray jsonArray = new JSONArray(matcher.group());
                for (int i = 0; i < jsonArray.length(); i++) {
                    result.add(jsonArray.getString(i));
                }
            } catch (Exception e) {
                System.err.println("Error parsing JSON: " + e.getMessage());
            }
        }

        return result;
    }

    private static String callClaudeAPI(String prompt) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // Create the request body
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", CLAUDE_MODEL);
        requestBody.put("max_tokens", 1000);

        JSONArray messages = new JSONArray();
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.put(userMessage);

        requestBody.put("messages", messages);

        // Build the request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CLAUDE_API_URL))
                .header("Content-Type", "application/json")
                .header("X-API-Key", API_KEY)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        // Send the request and get the response
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Parse the response
        JSONObject jsonResponse = new JSONObject(response.body());

        // Extract the assistant's message content
        return jsonResponse.getJSONObject("content").getString("text");
    }
}