package triangle.ast;

import java.util.List;

sealed public interface Expression extends Statement, Argument
        permits Expression.BinaryOp, Expression.FunCall, Expression.Identifier, Expression.IfExpression,
                Expression.LetExpression, Expression.LitArray, Expression.LitChar, Expression.LitInt, Expression.LitRecord,
                Expression.UnaryOp {

    sealed interface Identifier extends Expression permits Identifier.BasicIdentifier, Identifier.RecordAccess,
                                                           Identifier.ArraySubscript {

        record BasicIdentifier(String name) implements Identifier { }

        record RecordAccess(Identifier record, Identifier field) implements Identifier { }

        record ArraySubscript(Identifier array, Expression subscript) implements Identifier { }

        interface Visitor<ST, T,E extends Exception> {
            T visit(ST state, Identifier identifier) throws E;
        }

    }

    record LitInt(int value) implements Expression { }

    record LitChar(char value) implements Expression { }

    record LitArray(List<Expression> elements) implements Expression { }

    record LitRecord(List<RecordField> fields) implements Expression {
        public record RecordField(String name, Expression value) { }
    }

    record UnaryOp(String operator, Expression operand) implements Expression { }

    record BinaryOp(String operator, Expression leftOperand, Expression rightOperand) implements Expression { }

    record LetExpression(List<Declaration> declarations, Expression expression) implements Expression { }

    record IfExpression(Expression condition, Expression consequent, Expression alternative) implements Expression { }

    record FunCall(Identifier callable, List<Argument> arguments) implements Expression { }

    interface Visitor<ST,T,E extends Exception> {
        T visit(ST state, Expression expression) throws E;
    }
}
