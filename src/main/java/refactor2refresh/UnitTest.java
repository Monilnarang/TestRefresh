package refactor2refresh;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;

import java.util.ArrayList;

public class UnitTest {
    public String Name;
    public NodeList<Node> Statements;

    public ArrayList<TrieLikeNode> TrieLikeNodeList;

    public UnitTest(String name, NodeList<Node> statements) {
        Name = name;
        Statements = statements;
    }
}
