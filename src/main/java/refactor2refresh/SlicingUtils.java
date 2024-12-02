package refactor2refresh;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;

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
            } else if (stmt instanceof ClassOrInterfaceType) {
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
                stmtNode.type = StatementType.PRIMITIVE;
            } else {
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
            variable.value = getVariableValue(trie.children.get(2));
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

    public static boolean compare(TrieLikeNode trie1, TrieLikeNode trie2, HashMap<String, String> crossVariableMap) {
        if (trie1.type != trie2.type || trie1.children.size() != trie2.children.size())
            return false;
//        if(trie1.value != null || trie2.value != null) {
//
//        }
        if (trie1.type == StatementType.VARIABLE_DECLARATOR) {
            String name1 = trie1.children.get(1).value;
            String name2 = trie2.children.get(1).value;
            if(trie1.children.get(0).type == StatementType.PRIMITIVE) {
                if(trie1.children.get(0).type != trie2.children.get(0).type)
                    return false;
            } else {
                if(!trie1.children.get(0).children.get(0).value.equals(trie2.children.get(0).children.get(0).value))
                    return false;
            }
//            String type1 = trie1.children.get(0).children.get(0).value;
//            String type2 = trie2.children.get(0).children.get(0).value;
//            if (!type1.equals(type2)) {
//                return false;
//            }
            if (crossVariableMap.containsKey(name1)) {
                if (!crossVariableMap.get(name1).equals(name2)) {
                    return false;
                }
            } else {
                crossVariableMap.put(name1, name2);
            }
            return true;
        }
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
