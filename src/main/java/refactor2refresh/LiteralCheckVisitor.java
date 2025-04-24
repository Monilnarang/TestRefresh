package refactor2refresh;

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

public class LiteralCheckVisitor extends VoidVisitorAdapter<Void> {
    private boolean foundLiteral = false;

    public boolean hasFoundLiteral() {
        return foundLiteral;
    }

    // Check for string literals
    @Override
    public void visit(StringLiteralExpr n, Void arg) {
        foundLiteral = true;
        super.visit(n, arg);
    }

    // Check for char literals
    @Override
    public void visit(CharLiteralExpr n, Void arg) {
        foundLiteral = true;
        super.visit(n, arg);
    }

    // Check for integer literals
    @Override
    public void visit(IntegerLiteralExpr n, Void arg) {
        foundLiteral = true;
        super.visit(n, arg);
    }

    // Check for long literals
    @Override
    public void visit(LongLiteralExpr n, Void arg) {
        foundLiteral = true;
        super.visit(n, arg);
    }

    // Check for double literals
    @Override
    public void visit(DoubleLiteralExpr n, Void arg) {
        foundLiteral = true;
        super.visit(n, arg);
    }

    // Check for boolean literals
    @Override
    public void visit(BooleanLiteralExpr n, Void arg) {
        foundLiteral = true;
        super.visit(n, arg);
    }

    // Check for null literals
    @Override
    public void visit(NullLiteralExpr n, Void arg) {
        foundLiteral = true;
        super.visit(n, arg);
    }
}