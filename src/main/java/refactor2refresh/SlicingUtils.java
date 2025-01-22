package refactor2refresh;

import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;

enum StatementType {
    METHOD_CALL,
    EXPRESSION_STMT,
    VARIABLE_DECLARATION_EXPR,
    VARIABLE_DECLARATOR,
    CLASS_OR_INTERFACE_TYPE,
    SIMPLE_NAME, // check same string identifier
    OBJECT_CREATION_EXPR,
    LITERAL, // check same string value
    NAME_EXPR, // variables or objects -> store in map for parameterizing
    PRIMITIVE,
    POSTFIX_INCREMENT,
    POSTFIX_DECREMENT,
    PREFIX_INCREMENT,
    PREFIX_DECREMENT,
    ARRAY_TYPE, ARRAY_INITIALIZER_EXPR, BOOLEAN_LITERAL, FOR_STMT, IF_STMT, TRY_STMT, CATCH_CLAUSE, NULL_LITERAL, THROW_STMT, RETURN_STMT, SWITCH_STMT, SWITCH_ENTRY, LOCAL_CLASS_DECLARATION, LAMBDA_EXPR, CONDITIONAL_EXPR, ENCLOSED_EXPR, INSTANCEOF_EXPR, THIS_EXPR, SUPER_EXPR, CLASS_EXPR, TYPE_EXPR, MARKER_ANNOTATION, SINGLE_MEMBER_ANNOTATION, NORMAL_ANNOTATION, BREAK_STMT, CONTINUE_STMT, ASSERT_STMT, METHOD_REFERENCE, ARRAY_CREATION_EXPR, ARRAY_ACCESS_EXPR, CAST_EXPR, BLOCK_STMT, WHILE_STMT, PARAMETER, ARRAY_CREATION_LEVEL, ASSIGN_EXPR, WILDCARD_TYPE, FOR_EACH_STMT, METHOD_DECLARATION, FIELD_DECLARATION, UNKNOWN_TYPE,
}
public class SlicingUtils {
    private HashMap<String, ArrayList<String>> variableMap = new HashMap<String, ArrayList<String>>(); // key: variable name, value: variable type & variable value

    public static void addValue(TreeMap<Integer, ArrayList<LiteralStringValueExpr>> map, Integer key, LiteralStringValueExpr value) {
        // Check if the key exists
        if (!map.containsKey(key)) {
            // If the key does not exist, create a new ArrayList
            map.put(key, new ArrayList<>());
        }
        // Add the value to the ArrayList associated with the key
        map.get(key).add(value);
    }

    public static ArrayList<TrieLikeNode> createTrieFromStatementsWrapper(NodeList<Node> Statements, TreeMap<Integer, ArrayList<LiteralStringValueExpr>> valueSets) {
        HashMap<String, Integer> hardcodedMap = new HashMap<>();
        return createTrieFromStatements(Statements, hardcodedMap, valueSets);
    }

    public static ArrayList<TrieLikeNode> createTrieFromStatementsNew(NodeList<Node> Statements, HashMap<String, Integer> hardcodedMap, TreeMap<Integer, ArrayList<LiteralStringValueExpr>> valueSets) {
        ArrayList<TrieLikeNode> trieLikeNodes = new ArrayList<TrieLikeNode>();

        for (Node stmt : Statements) {
            TrieLikeNode stmtNode = new TrieLikeNode();

            if (stmt instanceof ExpressionStmt) {
                ExpressionStmt exprStmt = ((ExpressionStmt) stmt).clone();  // Clone the expression statement
                if (exprStmt.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int exprStmtSize = exprStmt.getChildNodes().size();
                    for (int i = 0; i < exprStmtSize; i++) {
                        childNodes.add(exprStmt.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.EXPRESSION_STMT;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof BinaryExpr) {
                BinaryExpr binaryExpr = ((BinaryExpr) stmt).clone();  // Clone the binary expression
                if (binaryExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int binaryExprSize = binaryExpr.getChildNodes().size();
                    for (int i = 0; i < binaryExprSize; i++) {
                        childNodes.add(binaryExpr.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.EXPRESSION_STMT;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            }
            else if (stmt instanceof FieldAccessExpr) {
                FieldAccessExpr fieldAccessExpr = ((FieldAccessExpr) stmt).clone();  // Clone the field access expression
                if (fieldAccessExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int fieldAccessExprSize = fieldAccessExpr.getChildNodes().size();
                    for (int i = 0; i < fieldAccessExprSize; i++) {
                        childNodes.add(fieldAccessExpr.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.EXPRESSION_STMT;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            }
            else if (stmt instanceof MethodCallExpr) {
                MethodCallExpr methodCallExpr = ((MethodCallExpr) stmt).clone();  // Clone the method call expression
                int methodCallExprSize = methodCallExpr.getChildNodes().size();
                if (methodCallExprSize > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    for (int i = 0; i < methodCallExprSize; i++) {
                        childNodes.add(methodCallExpr.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.METHOD_CALL;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof VariableDeclarationExpr) {
                VariableDeclarationExpr varDeclExpr = ((VariableDeclarationExpr) stmt).clone();  // Clone the variable declaration expression
                if (varDeclExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int varDeclExprSize = varDeclExpr.getChildNodes().size();
                    for (int i = 0; i < varDeclExprSize; i++) {
                        childNodes.add(varDeclExpr.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.VARIABLE_DECLARATION_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof VariableDeclarator) {
                VariableDeclarator varDecl = ((VariableDeclarator) stmt).clone();  // Clone the variable declarator
                if (varDecl.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int varDeclSize = varDecl.getChildNodes().size();
                    for (int i = 0; i < varDeclSize; i++) {
                        childNodes.add(varDecl.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.VARIABLE_DECLARATOR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof ArrayType) {
                ArrayType arrayType = ((ArrayType) stmt).clone();  // Clone the array type
                if (arrayType.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int arrayTypeSize = arrayType.getChildNodes().size();
                    for (int i = 0; i < arrayTypeSize; i++) {
                        childNodes.add(arrayType.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.ARRAY_TYPE;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            }
            else if (stmt instanceof ArrayInitializerExpr) {
                ArrayInitializerExpr arrayInitExpr = ((ArrayInitializerExpr) stmt).clone();  // Clone the array initializer expression
                StringBuilder arrayValue = new StringBuilder("{");
                boolean first = true;

                for (Node child : arrayInitExpr.getChildNodes()) {
                    if (!first) {
                        arrayValue.append(", ");
                    }
                    first = false;
                    arrayValue.append(child.toString());  // Convert each child node to string and append
                }
                arrayValue.append("}");

                stmtNode.value = arrayValue.toString();  // Store the entire array as a single value
                stmtNode.type = StatementType.LITERAL;  // Treat as a literal
            }
//            else if (stmt instanceof ArrayInitializerExpr) {
//                ArrayInitializerExpr arrayInitExpr = ((ArrayInitializerExpr) stmt).clone();  // Clone the array initializer expression
//                if (arrayInitExpr.getChildNodes().size() > 0) {
//                    NodeList<Node> childNodes = new NodeList<>();
//                    int arrayInitExprSize = arrayInitExpr.getChildNodes().size();
//                    for (int i = 0; i < arrayInitExprSize; i++) {
//                        childNodes.add(arrayInitExpr.getChildNodes().get(i).clone());  // Clone each child node
//                    }
//                    stmtNode.type = StatementType.ARRAY_INITIALIZER_EXPR;
//                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
//                }
//            }
            else if (stmt instanceof ClassOrInterfaceType) {
                ClassOrInterfaceType classOrInterfaceType = ((ClassOrInterfaceType) stmt).clone();  // Clone the class or interface type
                if (classOrInterfaceType.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int classOrInterfaceTypeSize = classOrInterfaceType.getChildNodes().size();
                    for (int i = 0; i < classOrInterfaceTypeSize; i++) {
                        childNodes.add(classOrInterfaceType.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.CLASS_OR_INTERFACE_TYPE;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof UnaryExpr) {
                UnaryExpr unaryExpr = ((UnaryExpr) stmt).clone();  // Clone the unary expression
                if (unaryExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int unaryExprSize = unaryExpr.getChildNodes().size();
                    for (int i = 0; i < unaryExprSize; i++) {
                        childNodes.add(unaryExpr.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = getUnaryExprType(unaryExpr);
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof NameExpr) {
                NameExpr nameExpr = ((NameExpr) stmt).clone();  // Clone the name expression
                if (nameExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int nameExprSize = nameExpr.getChildNodes().size();
                    for (int i = 0; i < nameExprSize; i++) {
                        childNodes.add(nameExpr.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.NAME_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof SimpleName) {
                SimpleName simpleName = ((SimpleName) stmt).clone();  // Clone the simple name
                stmtNode.type = StatementType.SIMPLE_NAME;
                stmtNode.value = simpleName.getIdentifier();
            } else if (stmt instanceof ObjectCreationExpr) {
                ObjectCreationExpr objCreationExpr = ((ObjectCreationExpr) stmt).clone();  // Clone the object creation expression
                if (objCreationExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int objCreationExprSize = objCreationExpr.getChildNodes().size();
                    for (int i = 0; i < objCreationExprSize; i++) {
                        childNodes.add(objCreationExpr.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.OBJECT_CREATION_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof LiteralStringValueExpr) {
                LiteralStringValueExpr literalExpr = ((LiteralStringValueExpr) stmt).clone();  // Clone the literal expression
                if (!hardcodedMap.containsKey(literalExpr.getValue())) {
                    hardcodedMap.put(literalExpr.getValue(), hardcodedMap.size() + 1);
                    addValue(valueSets, hardcodedMap.size(), literalExpr);
                }
                stmtNode.value = literalExpr.getValue();
                stmtNode.type = StatementType.LITERAL;
            } else if (stmt instanceof PrimitiveType) {
                PrimitiveType primitiveType = ((PrimitiveType) stmt).clone();  // Clone the primitive type
                if (primitiveType.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int primitiveTypeSize = primitiveType.getChildNodes().size();
                    for (int i = 0; i < primitiveTypeSize; i++) {
                        childNodes.add(primitiveType.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.PRIMITIVE;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof BooleanLiteralExpr) {
                BooleanLiteralExpr booleanExpr = ((BooleanLiteralExpr) stmt).clone();  // Clone the boolean literal expression
                stmtNode.type = StatementType.BOOLEAN_LITERAL;
                stmtNode.value = Boolean.toString(booleanExpr.getValue());  // Convert the boolean value to string ("true" or "false")
            }
            else if (stmt instanceof Modifier) {
                stmtNode.type = StatementType.PRIMITIVE;
            }
            else if (stmt instanceof ForStmt) {
                ForStmt forStmt = ((ForStmt) stmt).clone();  // Clone the for statement
                if (forStmt.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int forStmtSize = forStmt.getChildNodes().size();
                    for (int i = 0; i < forStmtSize; i++) {
                        childNodes.add(forStmt.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.FOR_STMT;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof IfStmt) {
                IfStmt ifStmt = ((IfStmt) stmt).clone();  // Clone the if statement
                if (ifStmt.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int ifStmtSize = ifStmt.getChildNodes().size();
                    for (int i = 0; i < ifStmtSize; i++) {
                        childNodes.add(ifStmt.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.IF_STMT;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof TryStmt) {
                TryStmt tryStmt = ((TryStmt) stmt).clone();  // Clone the try statement
                if (tryStmt.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int tryStmtSize = tryStmt.getChildNodes().size();
                    for (int i = 0; i < tryStmtSize; i++) {
                        childNodes.add(tryStmt.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.TRY_STMT;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof CatchClause) {
                CatchClause catchClause = ((CatchClause) stmt).clone();  // Clone the catch clause
                if (catchClause.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int catchClauseSize = catchClause.getChildNodes().size();
                    for (int i = 0; i < catchClauseSize; i++) {
                        childNodes.add(catchClause.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.CATCH_CLAUSE;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof NullLiteralExpr) {
                NullLiteralExpr nullExpr = ((NullLiteralExpr) stmt).clone();  // Clone the null literal expression
                stmtNode.type = StatementType.NULL_LITERAL;
                stmtNode.value = "null";
            } else if (stmt instanceof ThrowStmt) {
                ThrowStmt throwStmt = ((ThrowStmt) stmt).clone();  // Clone the throw statement
                if (throwStmt.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int throwStmtSize = throwStmt.getChildNodes().size();
                    for (int i = 0; i < throwStmtSize; i++) {
                        childNodes.add(throwStmt.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.THROW_STMT;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof ReturnStmt) {
                ReturnStmt returnStmt = ((ReturnStmt) stmt).clone();  // Clone the return statement
                if (returnStmt.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int returnStmtSize = returnStmt.getChildNodes().size();
                    for (int i = 0; i < returnStmtSize; i++) {
                        childNodes.add(returnStmt.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.RETURN_STMT;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof SwitchStmt) {
                SwitchStmt switchStmt = ((SwitchStmt) stmt).clone();  // Clone the switch statement
                if (switchStmt.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int switchStmtSize = switchStmt.getChildNodes().size();
                    for (int i = 0; i < switchStmtSize; i++) {
                        childNodes.add(switchStmt.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.SWITCH_STMT;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof SwitchEntry) {
                SwitchEntry switchEntry = ((SwitchEntry) stmt).clone();  // Clone the switch entry
                if (switchEntry.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int switchEntrySize = switchEntry.getChildNodes().size();
                    for (int i = 0; i < switchEntrySize; i++) {
                        childNodes.add(switchEntry.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.SWITCH_ENTRY;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof LocalClassDeclarationStmt) {
                LocalClassDeclarationStmt localClassDecl = ((LocalClassDeclarationStmt) stmt).clone();  // Clone the local class declaration
                if (localClassDecl.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int localClassDeclSize = localClassDecl.getChildNodes().size();
                    for (int i = 0; i < localClassDeclSize; i++) {
                        childNodes.add(localClassDecl.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.LOCAL_CLASS_DECLARATION;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof LambdaExpr) {
                LambdaExpr lambdaExpr = ((LambdaExpr) stmt).clone();  // Clone the lambda expression
                if (lambdaExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int lambdaExprSize = lambdaExpr.getChildNodes().size();
                    for (int i = 0; i < lambdaExprSize; i++) {
                        childNodes.add(lambdaExpr.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.LAMBDA_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            }
            else if (stmt instanceof ConditionalExpr) {
                ConditionalExpr conditionalExpr = ((ConditionalExpr) stmt).clone();
                if (conditionalExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int conditionalExprSize = conditionalExpr.getChildNodes().size();
                    for (int i = 0; i < conditionalExprSize; i++) {
                        childNodes.add(conditionalExpr.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.CONDITIONAL_EXPR;  // Ternary operator (?:)
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof EnclosedExpr) {
                // For handling parenthesized expressions
                EnclosedExpr enclosedExpr = ((EnclosedExpr) stmt).clone();
                if (enclosedExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int enclosedExprSize = enclosedExpr.getChildNodes().size();
                    for (int i = 0; i < enclosedExprSize; i++) {
                        childNodes.add(enclosedExpr.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.ENCLOSED_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof InstanceOfExpr) {
                // For handling instanceof keyword
                InstanceOfExpr instanceOfExpr = ((InstanceOfExpr) stmt).clone();
                if (instanceOfExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int instanceOfExprSize = instanceOfExpr.getChildNodes().size();
                    for (int i = 0; i < instanceOfExprSize; i++) {
                        childNodes.add(instanceOfExpr.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.INSTANCEOF_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof ThisExpr) {
                // For handling 'this' keyword
                ThisExpr thisExpr = ((ThisExpr) stmt).clone();
                if (thisExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int thisExprSize = thisExpr.getChildNodes().size();
                    for (int i = 0; i < thisExprSize; i++) {
                        childNodes.add(thisExpr.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.THIS_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof SuperExpr) {
                // For handling 'super' keyword
                SuperExpr superExpr = ((SuperExpr) stmt).clone();
                if (superExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int superExprSize = superExpr.getChildNodes().size();
                    for (int i = 0; i < superExprSize; i++) {
                        childNodes.add(superExpr.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.SUPER_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof ClassExpr) {
                // For handling '.class' expressions
                ClassExpr classExpr = ((ClassExpr) stmt).clone();
                if (classExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int classExprSize = classExpr.getChildNodes().size();
                    for (int i = 0; i < classExprSize; i++) {
                        childNodes.add(classExpr.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.CLASS_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof TypeExpr) {
                // For handling type expressions in method references and other contexts
                TypeExpr typeExpr = ((TypeExpr) stmt).clone();
                if (typeExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int typeExprSize = typeExpr.getChildNodes().size();
                    for (int i = 0; i < typeExprSize; i++) {
                        childNodes.add(typeExpr.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.TYPE_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof MarkerAnnotationExpr) {
                // For handling marker annotations (@Override, etc.)
                MarkerAnnotationExpr markerAnnotation = ((MarkerAnnotationExpr) stmt).clone();
                stmtNode.type = StatementType.MARKER_ANNOTATION;
                stmtNode.value = markerAnnotation.getNameAsString();
            } else if (stmt instanceof SingleMemberAnnotationExpr) {
                // For handling single member annotations (@SuppressWarnings("value"))
                SingleMemberAnnotationExpr singleMemberAnnotation = ((SingleMemberAnnotationExpr) stmt).clone();
                if (singleMemberAnnotation.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int annotationSize = singleMemberAnnotation.getChildNodes().size();
                    for (int i = 0; i < annotationSize; i++) {
                        childNodes.add(singleMemberAnnotation.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.SINGLE_MEMBER_ANNOTATION;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof NormalAnnotationExpr) {
                // For handling normal annotations with multiple pairs (@annotation(key1=value1, key2=value2))
                NormalAnnotationExpr normalAnnotation = ((NormalAnnotationExpr) stmt).clone();
                if (normalAnnotation.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int annotationSize = normalAnnotation.getChildNodes().size();
                    for (int i = 0; i < annotationSize; i++) {
                        childNodes.add(normalAnnotation.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.NORMAL_ANNOTATION;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            }
            else if (stmt instanceof BreakStmt) {
                BreakStmt breakStmt = ((BreakStmt) stmt).clone();
                if (breakStmt.getLabel().isPresent()) {
                    stmtNode.value = breakStmt.getLabel().get().toString();
                }
                stmtNode.type = StatementType.BREAK_STMT;
            } else if (stmt instanceof ContinueStmt) {
                ContinueStmt continueStmt = ((ContinueStmt) stmt).clone();
                if (continueStmt.getLabel().isPresent()) {
                    stmtNode.value = continueStmt.getLabel().get().toString();
                }
                stmtNode.type = StatementType.CONTINUE_STMT;
            } else if (stmt instanceof AssertStmt) {
                AssertStmt assertStmt = ((AssertStmt) stmt).clone();
                if (assertStmt.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int assertStmtSize = assertStmt.getChildNodes().size();
                    for (int i = 0; i < assertStmtSize; i++) {
                        childNodes.add(assertStmt.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.ASSERT_STMT;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            }
            else if (stmt instanceof MethodReferenceExpr) {
                MethodReferenceExpr methodRefExpr = ((MethodReferenceExpr) stmt).clone();
                if (methodRefExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int methodRefExprSize = methodRefExpr.getChildNodes().size();
                    for (int i = 0; i < methodRefExprSize; i++) {
                        childNodes.add(methodRefExpr.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.METHOD_REFERENCE;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof ArrayCreationExpr) {
                ArrayCreationExpr arrayCreationExpr = ((ArrayCreationExpr) stmt).clone();
                if (arrayCreationExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int arrayCreationExprSize = arrayCreationExpr.getChildNodes().size();
                    for (int i = 0; i < arrayCreationExprSize; i++) {
                        childNodes.add(arrayCreationExpr.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.ARRAY_CREATION_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof ArrayAccessExpr) {
                ArrayAccessExpr arrayAccessExpr = ((ArrayAccessExpr) stmt).clone();
                if (arrayAccessExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int arrayAccessExprSize = arrayAccessExpr.getChildNodes().size();
                    for (int i = 0; i < arrayAccessExprSize; i++) {
                        childNodes.add(arrayAccessExpr.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.ARRAY_ACCESS_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof CastExpr) {
                CastExpr castExpr = ((CastExpr) stmt).clone();
                if (castExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int castExprSize = castExpr.getChildNodes().size();
                    for (int i = 0; i < castExprSize; i++) {
                        childNodes.add(castExpr.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.CAST_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof BlockStmt) {
                BlockStmt blockStmt = ((BlockStmt) stmt).clone();
                if (blockStmt.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int blockStmtSize = blockStmt.getChildNodes().size();
                    for (int i = 0; i < blockStmtSize; i++) {
                        childNodes.add(blockStmt.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.BLOCK_STMT;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof WhileStmt) {
                WhileStmt whileStmt = ((WhileStmt) stmt).clone();
                if (whileStmt.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int whileStmtSize = whileStmt.getChildNodes().size();
                    for (int i = 0; i < whileStmtSize; i++) {
                        childNodes.add(whileStmt.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.WHILE_STMT;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof Parameter) {
                Parameter parameter = ((Parameter) stmt).clone();
                if (parameter.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int parameterSize = parameter.getChildNodes().size();
                    for (int i = 0; i < parameterSize; i++) {
                        childNodes.add(parameter.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.PARAMETER;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof ArrayCreationLevel) {
                ArrayCreationLevel arrayCreationLevel = ((ArrayCreationLevel) stmt).clone();
                if (arrayCreationLevel.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int arrayCreationLevelSize = arrayCreationLevel.getChildNodes().size();
                    for (int i = 0; i < arrayCreationLevelSize; i++) {
                        childNodes.add(arrayCreationLevel.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.ARRAY_CREATION_LEVEL;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof AssignExpr) {
                AssignExpr assignExpr = ((AssignExpr) stmt).clone();
                if (assignExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int assignExprSize = assignExpr.getChildNodes().size();
                    for (int i = 0; i < assignExprSize; i++) {
                        childNodes.add(assignExpr.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.ASSIGN_EXPR;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof WildcardType) {
                WildcardType wildcardType = ((WildcardType) stmt).clone();
                if (wildcardType.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int wildcardTypeSize = wildcardType.getChildNodes().size();
                    for (int i = 0; i < wildcardTypeSize; i++) {
                        childNodes.add(wildcardType.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.WILDCARD_TYPE;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof ForEachStmt) {
                ForEachStmt forEachStmt = ((ForEachStmt) stmt).clone();
                if (forEachStmt.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int forEachStmtSize = forEachStmt.getChildNodes().size();
                    for (int i = 0; i < forEachStmtSize; i++) {
                        childNodes.add(forEachStmt.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.FOR_EACH_STMT;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof MethodDeclaration) {
                MethodDeclaration methodDecl = ((MethodDeclaration) stmt).clone();
                if (methodDecl.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int methodDeclSize = methodDecl.getChildNodes().size();
                    for (int i = 0; i < methodDeclSize; i++) {
                        childNodes.add(methodDecl.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.METHOD_DECLARATION;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof FieldDeclaration) {
                FieldDeclaration fieldDecl = ((FieldDeclaration) stmt).clone();
                if (fieldDecl.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int fieldDeclSize = fieldDecl.getChildNodes().size();
                    for (int i = 0; i < fieldDeclSize; i++) {
                        childNodes.add(fieldDecl.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.FIELD_DECLARATION;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            } else if (stmt instanceof UnknownType) {
                UnknownType unknownType = ((UnknownType) stmt).clone();
                if (unknownType.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int unknownTypeSize = unknownType.getChildNodes().size();
                    for (int i = 0; i < unknownTypeSize; i++) {
                        childNodes.add(unknownType.getChildNodes().get(i).clone());
                    }
                    stmtNode.type = StatementType.UNKNOWN_TYPE;
                    stmtNode.children = createTrieFromStatementsNew(childNodes, hardcodedMap, valueSets);
                }
            }
            else {
                throw new IllegalStateException("Unexpected value: " + stmt);
            }

            trieLikeNodes.add(stmtNode);
        }
        return trieLikeNodes;
    }
    public static ArrayList<TrieLikeNode> createTrieFromStatements(NodeList<Node> Statements, HashMap<String, Integer> hardcodedMap, TreeMap<Integer, ArrayList<LiteralStringValueExpr>> valueSets) { // one array per test
        ArrayList<TrieLikeNode> trieLikeNodes = new ArrayList<TrieLikeNode>();
        for (Node stmt : Statements) {
            TrieLikeNode stmtNode = new TrieLikeNode();

            if (stmt instanceof ExpressionStmt) {
                ExpressionStmt exprStmt = (ExpressionStmt) stmt;
                if (exprStmt.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int exprStmtSize = exprStmt.getChildNodes().size();
                    for (int i = 0; i < exprStmtSize; i++) {
                        childNodes.add(exprStmt.getChildNodes().get(0));
                    }
                    stmtNode.type = StatementType.EXPRESSION_STMT;
                    stmtNode.children = createTrieFromStatements(childNodes, hardcodedMap, valueSets);
                }
//                System.out.println(exprStmt.getExpression());
            } // call children
            else if (stmt instanceof BinaryExpr) {
                BinaryExpr binaryExpr = (BinaryExpr) stmt;
                if (binaryExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int binaryExprSize = binaryExpr.getChildNodes().size();
                    for (int i = 0; i < binaryExprSize; i++) {
                        childNodes.add(binaryExpr.getChildNodes().get(0));
                    }
                    stmtNode.type = StatementType.EXPRESSION_STMT;
                    stmtNode.children = createTrieFromStatements(childNodes, hardcodedMap, valueSets);
                }
            }
            else if (stmt instanceof MethodCallExpr) {
                MethodCallExpr methodCallExpr = (MethodCallExpr) stmt;
                int methodCallExprSize = methodCallExpr.getChildNodes().size();
                if (methodCallExprSize > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    for (int i = 0; i < methodCallExprSize; i++) {
                        childNodes.add(methodCallExpr.getChildNodes().get(0));
                    }
                    stmtNode.type = StatementType.METHOD_CALL;
                    stmtNode.children = createTrieFromStatements(childNodes, hardcodedMap, valueSets);
                }
//                System.out.println(methodCallExpr.getName());

            } // call children
            else if (stmt instanceof VariableDeclarationExpr) {
                VariableDeclarationExpr varDeclExpr = (VariableDeclarationExpr) stmt;
                if (varDeclExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int varDeclExprSize = varDeclExpr.getChildNodes().size();
                    for (int i = 0; i < varDeclExprSize; i++) {
                        childNodes.add(varDeclExpr.getChildNodes().get(0));
                    }
                    stmtNode.type = StatementType.VARIABLE_DECLARATION_EXPR;
                    stmtNode.children = createTrieFromStatements(childNodes, hardcodedMap, valueSets);
                }
//                System.out.println(varDeclExpr.getVariables());
            } // call children
            else if (stmt instanceof VariableDeclarator) {
                VariableDeclarator varDecl = (VariableDeclarator) stmt;
                if (varDecl.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int varDeclSize = varDecl.getChildNodes().size();
                    for (int i = 0; i < varDeclSize; i++) {
                        childNodes.add(varDecl.getChildNodes().get(0));
                    }
//                    addVariableToMap(childNodes);
                    stmtNode.type = StatementType.VARIABLE_DECLARATOR;
                    stmtNode.children = createTrieFromStatements(childNodes, hardcodedMap, valueSets);
                }
//                System.out.println(varDecl.getName());
            } // call children
            else if (stmt instanceof ClassOrInterfaceType) {
                ClassOrInterfaceType classOrInterfaceType = (ClassOrInterfaceType) stmt;
                if (classOrInterfaceType.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int classOrInterfaceTypeSize = classOrInterfaceType.getChildNodes().size();
                    for (int i = 0; i < classOrInterfaceTypeSize; i++) {
                        childNodes.add(classOrInterfaceType.getChildNodes().get(0));
                    }
                    stmtNode.type = StatementType.CLASS_OR_INTERFACE_TYPE;
                    stmtNode.children = createTrieFromStatements(childNodes, hardcodedMap, valueSets);
                }
//                System.out.println(classOrInterfaceType.getName());
            } // call children
            else if (stmt instanceof UnaryExpr) {
                UnaryExpr unaryExpr = (UnaryExpr) stmt;
                if (unaryExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int unaryExprSize = unaryExpr.getChildNodes().size();
                    for (int i = 0; i < unaryExprSize; i++) {
                        childNodes.add(unaryExpr.getChildNodes().get(0));
                    }
                    stmtNode.type = getUnaryExprType(unaryExpr);
                    stmtNode.children = createTrieFromStatements(childNodes, hardcodedMap, valueSets);
                }
            }
            else if (stmt instanceof NameExpr) {
                NameExpr nameExpr = (NameExpr) stmt;
                if (nameExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int nameExprSize = nameExpr.getChildNodes().size();
                    for (int i = 0; i < nameExprSize; i++) {
                        childNodes.add(nameExpr.getChildNodes().get(0));
                    }
                    stmtNode.type = StatementType.NAME_EXPR;
                    stmtNode.children = createTrieFromStatements(childNodes, hardcodedMap, valueSets);
                }
//                System.out.println(nameExpr.getName());
            } // call children
            else if (stmt instanceof SimpleName) {
                SimpleName simpleName = (SimpleName) stmt;
                stmtNode.type = StatementType.SIMPLE_NAME;
                stmtNode.value = simpleName.getIdentifier();
//                System.out.println(simpleName.asString());
            } // save current value
            else if (stmt instanceof ObjectCreationExpr) {
                ObjectCreationExpr objCreationExpr = (ObjectCreationExpr) stmt;
                if (objCreationExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int objCreationExprSize = objCreationExpr.getChildNodes().size();
                    for (int i = 0; i < objCreationExprSize; i++) {
                        childNodes.add(objCreationExpr.getChildNodes().get(0));
                    }
                    stmtNode.type = StatementType.OBJECT_CREATION_EXPR;
                    stmtNode.children = createTrieFromStatements(childNodes, hardcodedMap, valueSets);
                }
//                System.out.println(objCreationExpr.getType());
            } // call children
            else if (stmt instanceof LiteralStringValueExpr) {
                LiteralStringValueExpr literalExpr = ((LiteralStringValueExpr) stmt).clone();
                if(!hardcodedMap.containsKey(literalExpr.getValue())) {
                    hardcodedMap.put(literalExpr.getValue(), hardcodedMap.size() + 1);
                    addValue(valueSets, hardcodedMap.size(), literalExpr);
                }
                stmtNode.value = literalExpr.getValue();
                stmtNode.type = StatementType.LITERAL;
//                System.out.println(literalExpr.getValue());
            } // save current value
            else if (stmt instanceof PrimitiveType) {
                stmtNode.type =  StatementType.PRIMITIVE;
            }
            else if (stmt instanceof FieldAccessExpr) {
                FieldAccessExpr fieldAccessExpr = ((FieldAccessExpr) stmt).clone();  // Clone the field access expression
                if (fieldAccessExpr.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int fieldAccessExprSize = fieldAccessExpr.getChildNodes().size();
                    for (int i = 0; i < fieldAccessExprSize; i++) {
                        childNodes.add(fieldAccessExpr.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.EXPRESSION_STMT;
                    stmtNode.children = createTrieFromStatements(childNodes, hardcodedMap, valueSets);
                }
            }
            else if (stmt instanceof BlockStmt) {
                BlockStmt blockStmt = ((BlockStmt) stmt).clone();  // Clone the block statement
                if (blockStmt.getChildNodes().size() > 0) {
                    NodeList<Node> childNodes = new NodeList<>();
                    int blockStmtSize = blockStmt.getChildNodes().size();
                    for (int i = 0; i < blockStmtSize; i++) {
                        childNodes.add(blockStmt.getChildNodes().get(i).clone());  // Clone each child node
                    }
                    stmtNode.type = StatementType.BLOCK_STMT;
                    stmtNode.children = createTrieFromStatements(childNodes, hardcodedMap, valueSets);
                }
            }
            else {
                throw new IllegalStateException("Unexpected value: " + stmt);
            }
            trieLikeNodes.add(stmtNode);
        }
        return trieLikeNodes;
    }

    private static StatementType getUnaryExprType(UnaryExpr unaryExpr) {
        if (unaryExpr.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT) {
            return StatementType.POSTFIX_DECREMENT;
        } else if (unaryExpr.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT) {
            return StatementType.POSTFIX_INCREMENT;
        } else if (unaryExpr.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT) {
            return StatementType.PREFIX_DECREMENT;
        } else if (unaryExpr.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT) {
            return StatementType.PREFIX_INCREMENT;
        }
        return null;
    }

    // dfs on trie of one test: pass parent
    // if parent conditions
    // if parent is variable declaration: store in map
    //
    //
    // else: dfs on children


    // map: key: variable name, value: variable type, variable value : obj init,  int char : string store
    // when parent is variable declarator
    // unordered map : variableName (string) 1st child of type SimpleName -> variableType (string) 1st child of type class_or_interface, variableValue (vector<string>) 2nd children under object_creation_expression
    // "x" -> "AddObj", {"AddObj", "5"}
    // "y",  "AddObj", {"AddObj", "7"}

    // "x1" -> "AddObj", {"AddObj", "15"}
    // "y1" ->  "AddObj", {"AddObj", "17"}

    // x-> x1
    // y-> y1
    public class Variable {
        String name;
        String type;
        ArrayList<String> value;
    }

    private ArrayList<String> getVariableValue(TrieLikeNode trie) {
        ArrayList<String> ans = new ArrayList<String>();
        for (TrieLikeNode child : trie.children) {
            if (child.value == null) {
                ans.addAll(getVariableValue(child));
            } else {
                ans.add(child.value);
            }
        }
        return ans;
    }

    private void addVariablesAndHardCodedToMap(TrieLikeNode trie, HashMap<String, Variable> variableMap) {
        if (trie == null)
            return;
        if (trie.type == StatementType.VARIABLE_DECLARATOR) {
            Variable variable = new Variable();
            if(trie.children.get(0).type == StatementType.PRIMITIVE) {
                variable.type = trie.children.get(0).type.toString();
            } else {
                variable.type = trie.children.get(0).children.get(0).value;
            }

            variable.name = trie.children.get(1).value;
//            try {
                variable.value = getVariableValue(trie.children.get(2));
//            } catch (Exception e) {
//                System.out.println(e.getCause());
//            }

            variableMap.put(variable.name, variable);
        } else {
            for (TrieLikeNode child : trie.children) {
                addVariablesAndHardCodedToMap(child, variableMap);
            }

        }

    }

    //        if(node.type == StatementType.VARIABLE_DECLARATOR) {
//            Variable variable = new Variable();
//            variable.name = node.children.get(0).value;
//            variable.type = node.children.get(1).children.get(0).value;
//            variable.value = new ArrayList<>();
//            for(int i=0; i<node.children.get(1).children.get(1).children.size(); i++) {
//                variable.value.add(node.children.get(1).children.get(1).children.get(i).value);
//            }
//            variableMap.put(variable.name, variable);
//        }
//        else {
//            addVariablesAndHardCodedToMap(node.children, variableMap);
//        }
    // todo hardcoded for parameters

    // todo implement
    // 1st child -> type; 2nd child -> name; 3rd child -> value & type
    // if type is variable declator -> add to map

    // call dfs on children


    public void populateVariableMapFromTrie(ArrayList<TrieLikeNode> trie, HashMap<String, Variable> variableMap) {
        for (TrieLikeNode node : trie) {
            addVariablesAndHardCodedToMap(node, variableMap);
        }
    }

    public static boolean compareTrieLists(ArrayList<TrieLikeNode> trie1, ArrayList<TrieLikeNode> trie2, HashMap<String, String> crossVariableMap) {
        if (trie1.size() != trie2.size())
            return false;
        for (int i = 0; i < trie1.size(); i++) {
            if (!compare(trie1.get(i), trie2.get(i), crossVariableMap))
                return false;
        }
        return true;
    }

    public static boolean checkIfTypeOfClassVariableDeclaratorIsSame(TrieLikeNode trie1, TrieLikeNode trie2) {
        ArrayList<TrieLikeNode> trie1Children = trie1.children.get(0).children;
        ArrayList<TrieLikeNode> trie2Children = trie2.children.get(0).children;
        String type1 = "";
        String type2 = "";
        for (int i = 0; i < trie1Children.size(); i++) {
            if (trie1Children.get(i).type == StatementType.SIMPLE_NAME) {
                type1 = trie1Children.get(i).value;
                type2 = trie2Children.get(i).value;
                break;
            }
        }
        try {
            if (!type1.equals(type2))
                return false;
        } catch (Exception e) {
            System.out.println("exception 1");
            System.out.println(e.getCause());
        }
        return true;
    }

    public static boolean checkIfTypeOfClassVariableValueIsSame(TrieLikeNode trie1, TrieLikeNode trie2) {
        if (trie1.children.size()<2)
            return true;
        if (trie1.children.get(2).type != trie2.children.get(2).type)
            return false;
        return true;
    }


    public static boolean compare(TrieLikeNode trie1, TrieLikeNode trie2, HashMap<String, String> crossVariableMap) {
        if (trie1.type != trie2.type || trie1.children.size() != trie2.children.size())
            return false;

        if (trie1.type == StatementType.VARIABLE_DECLARATOR) {
            String name1 = trie1.children.get(1).value;
            String name2 = trie2.children.get(1).value;
            if (name1.equals("fmt")) {
                System.out.println("fmt");
            }
            if(trie1.children.get(0).type != trie2.children.get(0).type) {
                return false;
            } else if (trie1.children.get(0).type == StatementType.CLASS_OR_INTERFACE_TYPE) {
                if (!checkIfTypeOfClassVariableDeclaratorIsSame(trie1, trie2))
                    return false;
                if (!checkIfTypeOfClassVariableValueIsSame(trie1, trie2))
                    return false;
                // add check for value => if method call => check if method call is same
                // method calls are in children like a stack => check if all children are same, values can be diff
            } else if (trie1.children.get(0).type == StatementType.ARRAY_TYPE) {
                // can't check if type of array is same
                // todo why checking size and elements of arrays?
                    if(trie1.children.get(2).children.size() != trie2.children.get(2).children.size())
                        return false;
                    for (int i=0; i<trie1.children.get(2).children.size(); i++) {
                        if(!trie1.children.get(2).children.get(i).value.equals(trie2.children.get(2).children.get(i).value))
                            return false;
                    }
            } else if(trie1.children.get(0).type == StatementType.PRIMITIVE) {
                    System.out.println("primitive");
            } else {
                    System.out.println("handle new type ");
            }
            if (crossVariableMap.containsKey(name1)) {
                if (!crossVariableMap.get(name1).equals(name2)) {
                    return false;
                }
            } else {
                crossVariableMap.put(name1, name2);
            }
            return true;
        } else {
//            System.out.println("handle new type ");
            // missing were method calls, rest are more or less covered in VariableDeclarator
        }
        // todo check case: in case of ArrayType, both values were null and false was returned
        if (crossVariableMap.containsKey(trie1.value) && !crossVariableMap.get(trie1.value).equals(trie2.value))
            return false;
        for (int i = 0; i < trie1.children.size(); i++) {
            if (!compare(trie1.children.get(i), trie2.children.get(i), crossVariableMap))
                return false;
        }
        // compare structure of trees : if not -> return false
        // if reached variables -> should be in sync everywhere : if not -> return false

        // if literal can differ
        // if simple name and can be found in Variablemap => can differ
        // if not found in Syncmap -> add to map
        // else -> should be same
        return true;
    }
}
