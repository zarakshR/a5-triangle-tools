package triangle.ast;

import java.util.List;
import java.util.Optional;

sealed public interface Statement permits Expression, Statement.AssignStatement, Statement.IfStatement, Statement.LetStatement,
                                          Statement.LoopWhileStatement, Statement.RepeatUntilStatement,
                                          Statement.RepeatWhileStatement, Statement.StatementBlock, Statement.WhileStatement {

    interface Visitor<ST, T,E extends Exception> {

        T visit(ST state, Statement statement) throws E;

    }

    record StatementBlock(List<Statement> statements) implements Statement { }

    record LetStatement(List<Declaration> declarations, Statement statement) implements Statement { }

    record IfStatement(Expression condition, Optional<Statement> consequent, Optional<Statement> alternative)
            implements Statement { }

    record WhileStatement(Expression condition, Statement body) implements Statement { }

    record LoopWhileStatement(Expression condition, Statement loopBody, Statement doBody) implements Statement { }

    record RepeatWhileStatement(Expression condition, Statement body) implements Statement { }

    record RepeatUntilStatement(Expression condition, Statement body) implements Statement { }

    record AssignStatement(Expression.Identifier identifier, Expression expression) implements Statement { }

}
