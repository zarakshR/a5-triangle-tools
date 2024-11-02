package triangle.ast;

import java.util.List;
import java.util.Optional;

sealed public interface Statement permits Expression, Statement.AssignStatement, Statement.IfStatement, Statement.LetStatement,
                                          Statement.LoopWhileStatement, Statement.RepeatUntilStatement,
                                          Statement.RepeatWhileStatement, Statement.StatementBlock, Statement.WhileStatement {

    interface Visitor<ST, T> {

        T visit(ST state, Statement statement);

    }

    record StatementBlock(List<Statement> statements) implements Statement { }

    record LetStatement(List<Declaration> declarations, Statement statement) implements Statement { }

    record IfStatement(Expression condition, Optional<Statement> consequent, Optional<Statement> alternative)
            implements Statement { }

    record WhileStatement(Expression condition, Statement body) implements Statement { }

    record LoopWhileStatement(Expression condition, Statement statement1, Statement statement2) implements Statement { }

    record RepeatWhileStatement(Expression condition, Statement statement) implements Statement { }

    record RepeatUntilStatement(Expression condition, Statement statement) implements Statement { }

    record AssignStatement(Expression.Identifier identifier, Expression expression) implements Statement { }

}
