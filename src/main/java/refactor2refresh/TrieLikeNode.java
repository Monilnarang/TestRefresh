package refactor2refresh;


import java.util.ArrayList;

public class TrieLikeNode {
    public StatementType type;
    public ArrayList<TrieLikeNode> children;
    public String value;
    public TrieLikeNode() {
        this.children = new ArrayList<>();
    }
}
