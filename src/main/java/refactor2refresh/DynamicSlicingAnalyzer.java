package refactor2refresh;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.*;
import java.util.stream.Collectors;

public class DynamicSlicingAnalyzer {
    static void performDynamicSlicing(MethodDeclaration method, MethodCallExpr assertion) {
        // Create a dynamic execution context to track variable states and dependencies
        DynamicExecutionContext executionContext = new DynamicExecutionContext();

        // Collect variables used in the assertion
        Set<String> requiredVariables = getVariablesUsedInAssertion(assertion);

        // Instrument the method for dynamic tracking
        instrumentMethodForDynamicSlicing(method, executionContext);

        // Execute the method and capture runtime information
        executeMethodWithDynamicTracking(method, executionContext);

        // Perform backward slice based on dynamic dependencies
        performBackwardSliceBasedOnDynamicDependencies(method, requiredVariables, executionContext);
    }

    private static class DynamicExecutionContext {
        // Track variable values at each statement
        private Map<String, Object> variableValues = new HashMap<>();

        // Track dynamic dependencies between variables
        private Map<String, Set<String>> variableDependencies = new HashMap<>();

        public void recordVariableValue(String variableName, Object value) {
            variableValues.put(variableName, value);
        }

        public void recordVariableDependency(String dependentVariable, String sourceVariable) {
            variableDependencies.computeIfAbsent(dependentVariable, k -> new HashSet<>())
                    .add(sourceVariable);
        }

        public Set<String> getDynamicDependencies(String variable) {
            Set<String> directDependencies = variableDependencies.getOrDefault(variable, new HashSet<>());
            Set<String> allDependencies = new HashSet<>(directDependencies);

            // Recursively collect transitive dependencies
            for (String dep : directDependencies) {
                allDependencies.addAll(getDynamicDependencies(dep));
            }

            return allDependencies;
        }
    }

    private static void instrumentMethodForDynamicSlicing(MethodDeclaration method, DynamicExecutionContext context) {
        // Modify method to add instrumentation for tracking variable values and dependencies
        method.getBody().ifPresent(body -> {
            for (Statement stmt : body.getStatements()) {
                if (stmt.isExpressionStmt()) {
                    ExpressionStmt exprStmt = stmt.asExpressionStmt();
                    Expression expr = exprStmt.getExpression();

                    if (expr.isAssignExpr()) {
                        AssignExpr assignExpr = expr.asAssignExpr();
                        String targetVar = assignExpr.getTarget().toString();

                        // Track dependencies for assignment expressions
                        trackAssignmentDependencies(assignExpr, targetVar, context);
                    } else if (expr.isVariableDeclarationExpr()) {
                        // Track dependencies for variable declarations
                        trackVariableDeclarationDependencies(expr.asVariableDeclarationExpr(), context);
                    }
                }
            }
        });
    }

    private static void trackAssignmentDependencies(AssignExpr assignExpr, String targetVar, DynamicExecutionContext context) {
        // Collect variables used in the right-hand side of the assignment
        Set<String> sourceVars = getVariablesUsedInExpression(assignExpr.getValue());

        for (String sourceVar : sourceVars) {
            context.recordVariableDependency(targetVar, sourceVar);
        }
    }

    private static void trackVariableDeclarationDependencies(VariableDeclarationExpr declExpr, DynamicExecutionContext context) {
        declExpr.getVariables().forEach(var -> {
            String varName = var.getNameAsString();
            var.getInitializer().ifPresent(initializer -> {
                Set<String> sourceVars = getVariablesUsedInExpression(initializer);

                for (String sourceVar : sourceVars) {
                    context.recordVariableDependency(varName, sourceVar);
                }
            });
        });
    }

    private static void executeMethodWithDynamicTracking(MethodDeclaration method, DynamicExecutionContext context) {
        // Simulate method execution and record variable values
        method.getBody().ifPresent(body -> {
            for (Statement stmt : body.getStatements()) {
                if (stmt.isExpressionStmt()) {
                    ExpressionStmt exprStmt = stmt.asExpressionStmt();
                    Expression expr = exprStmt.getExpression();

                    if (expr.isAssignExpr()) {
                        AssignExpr assignExpr = expr.asAssignExpr();
                        String targetVar = assignExpr.getTarget().toString();

                        // Simulate capturing the value
                        Object value = evaluateExpression(assignExpr.getValue());
                        context.recordVariableValue(targetVar, value);
                    } else if (expr.isVariableDeclarationExpr()) {
                        // Similar tracking for variable declarations
                        expr.asVariableDeclarationExpr().getVariables().forEach(var -> {
                            String varName = var.getNameAsString();
                            var.getInitializer().ifPresent(initializer -> {
                                Object value = evaluateExpression(initializer);
                                context.recordVariableValue(varName, value);
                            });
                        });
                    }
                }
            }
        });
    }

    private static void performBackwardSliceBasedOnDynamicDependencies(
            MethodDeclaration method,
            Set<String> requiredVariables,
            DynamicExecutionContext context
    ) {
        // Collect all dynamic dependencies for required variables
        Set<String> allRequiredVariables = new HashSet<>(requiredVariables);
        for (String var : requiredVariables) {
            allRequiredVariables.addAll(context.getDynamicDependencies(var));
        }

        // Remove statements that do not contribute to required variables
        method.getBody().ifPresent(body -> {
            List<Statement> statements = body.getStatements();
            for (int i = statements.size() - 1; i >= 0; i--) {
                Statement stmt = statements.get(i);

                if (stmt.isExpressionStmt()) {
                    ExpressionStmt exprStmt = stmt.asExpressionStmt();
                    Expression expr = exprStmt.getExpression();

                    boolean shouldRemove = true;
                    if (expr.isAssignExpr()) {
                        AssignExpr assignExpr = expr.asAssignExpr();
                        String varName = assignExpr.getTarget().toString();

                        shouldRemove = !allRequiredVariables.contains(varName);
                    } else if (expr.isVariableDeclarationExpr()) {
                        // Check if any declared variables are in required set
                        shouldRemove = expr.asVariableDeclarationExpr().getVariables().stream()
                                .noneMatch(var -> allRequiredVariables.contains(var.getNameAsString()));
                    }

                    if (shouldRemove) {
                        stmt.remove();
                    }
                }
            }
        });
    }

    // Comprehensive method to extract variables used in an assertion
    private static Set<String> getVariablesUsedInAssertion(MethodCallExpr assertion) {
        Set<String> variables = new HashSet<>();

        // Visitor to collect variable names from the assertion
        assertion.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(NameExpr n, Void arg) {
                variables.add(n.getNameAsString());
                super.visit(n, arg);
            }

            @Override
            public void visit(FieldAccessExpr n, Void arg) {
                variables.add(n.getName().getIdentifier());
                super.visit(n, arg);
            }
        }, null);

        return variables;
    }

    // Comprehensive method to extract variables used in an expression
    private static Set<String> getVariablesUsedInExpression(Expression expr) {
        if (expr == null) {
            return Collections.emptySet();
        }

        Set<String> variables = new HashSet<>();

        // Visitor to collect variable names from the expression
        expr.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(NameExpr n, Void arg) {
                variables.add(n.getNameAsString());
                super.visit(n, arg);
            }

            @Override
            public void visit(FieldAccessExpr n, Void arg) {
                variables.add(n.getName().getIdentifier());
                super.visit(n, arg);
            }
        }, null);

        return variables;
    }

    // Basic expression evaluation method (simplified)
    private static Object evaluateExpression(Expression expr) {
        // This is a simplified evaluation - in a real scenario,
        // you'd need more sophisticated runtime evaluation

        if (expr == null) {
            return null;
        }

        // Handle literal expressions
        if (expr.isLiteralExpr()) {
            LiteralExpr literalExpr = expr.asLiteralExpr();
            if (literalExpr.isIntegerLiteralExpr()) {
                return Integer.parseInt(literalExpr.asIntegerLiteralExpr().getValue());
            }
            if (literalExpr.isDoubleLiteralExpr()) {
                return Double.parseDouble(literalExpr.asDoubleLiteralExpr().getValue());
            }
            if (literalExpr.isStringLiteralExpr()) {
                return literalExpr.asStringLiteralExpr().getValue();
            }
            if (literalExpr.isBooleanLiteralExpr()) {
                return literalExpr.asBooleanLiteralExpr().getValue();
            }
        }

        // Handle name expressions (variables)
        if (expr.isNameExpr()) {
            return expr.asNameExpr().getNameAsString();
        }

        // Handle binary expressions
        if (expr.isBinaryExpr()) {
            BinaryExpr binaryExpr = expr.asBinaryExpr();
            Object left = evaluateExpression(binaryExpr.getLeft());
            Object right = evaluateExpression(binaryExpr.getRight());

            // Simplified binary operation evaluation
            switch (binaryExpr.getOperator()) {
                case PLUS:
                    if (left instanceof String || right instanceof String) {
                        return String.valueOf(left) + String.valueOf(right);
                    }
                    if (left instanceof Number && right instanceof Number) {
                        return ((Number)left).doubleValue() + ((Number)right).doubleValue();
                    }
                    break;
                case MINUS:
                    if (left instanceof Number && right instanceof Number) {
                        return ((Number)left).doubleValue() - ((Number)right).doubleValue();
                    }
                    break;
                // Add more operators as needed
            }
        }

        // Fallback for complex expressions
        return expr.toString();
    }
}