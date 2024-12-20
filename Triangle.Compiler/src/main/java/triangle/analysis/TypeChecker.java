package triangle.analysis;

import triangle.repr.Argument;
import triangle.repr.Declaration;
import triangle.repr.Expression;
import triangle.repr.Parameter;
import triangle.repr.SourcePosition;
import triangle.repr.Statement;
import triangle.repr.Type;
import triangle.repr.TypeSig;
import triangle.util.StdEnv;
import triangle.util.SymbolTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static triangle.repr.Type.*;

// TypeChecker assumes:
//  no duplicate record fields
//  no duplicate parameters
//  no duplicate declarations
//  all uses of an identifier have a corresponding binding
//  func/proc parameters are only ever supplied with func/proc arguments
//  var parameters are only ever supplied with var arguments
//  record literals are canonicalized

// TypeChecker ensures:
//  there are no type errors in the program
//  all Typeable nodes in the AST are annotated with their types; i.e., it produces an explicitly-typed AST
//  the program does not attempt to return a function/procedure as a value
//  record types are canonicalized

// TypeChecker canonicalizes records so that:
//  record c : Char, a : Integer end
// is equivalent to
//  record a : Integer, c : Char end
// but not equivalent to
//  record a : Boolean, c : Char nd
// i.e, the canonical form of a record type has its fields sorted in alphabetical order
// We are guaranteed, due to not allowing duplicate record fields that this canonical form is unique

// WARNING: this class uses exception as control flow; this is to allow checking to resume from a known "safe point"
public final class TypeChecker {

    private final List<SemanticException> errors = new ArrayList<>();
    private final SymbolTable<Type, Void> terms  = new SymbolTable<>(StdEnv.STD_TERMS, null);
    private final SymbolTable<Type, Void> types  = new SymbolTable<>(StdEnv.STD_TYPES, null);

    public List<SemanticException> getErrors() {
        return errors;
    }

    public void typecheck(Statement statement) {
        // statements never introduce new bindings (outside their own bodies) so we can always recover from semantic exceptions
        // and continue typechecking the rest of the program
        switch (statement) {
            case Statement.AssignStatement assignStatement -> {
                try {
                    typecheck(assignStatement.expression());
                    typecheck(assignStatement.identifier());
                } catch (SemanticException e) {
                    errors.add(e);
                }
            }
            case Statement.ExpressionStatement expressionStatement -> {
                try {
                    typecheck(expressionStatement.expression());
                } catch (SemanticException e) {
                    errors.add(e);
                }
            }
            case Statement.IfStatement ifStatement -> {
                try {
                    typecheck(ifStatement.condition());
                    ifStatement.consequent().ifPresent(this::typecheck);
                    ifStatement.alternative().ifPresent(this::typecheck);

                    Type cT = ifStatement.condition().getType().baseType();
                    if (!(cT instanceof Type.PrimType.BoolType)) {
                        throw new SemanticException.TypeError(ifStatement.sourcePosition(), cT, BOOL_TYPE);
                    }
                } catch (SemanticException e) {
                    errors.add(e);
                }
            }
            case Statement.LetStatement letStatement -> {
                try {
                    terms.enterNewScope(null);
                    types.enterNewScope(null);

                    for (Declaration declaration : letStatement.declarations()) {
                        try {
                            bindDeclaration(declaration);
                        } catch (SemanticException e) {
                            errors.add(e);
                        }
                    }

                    typecheck(letStatement.statement());
                } finally {
                    terms.exitScope();
                    types.exitScope();
                }
            }
            case Statement.LoopWhileStatement loopWhileStatement -> {
                try {
                    typeCheckLoop(
                            loopWhileStatement.sourcePosition(), loopWhileStatement.condition(), loopWhileStatement.loopBody());
                    typecheck(loopWhileStatement.doBody());
                } catch (SemanticException e) {
                    errors.add(e);
                }
            }
            case Statement.RepeatUntilStatement repeatUntilStatement -> {
                try {
                    typeCheckLoop(
                            repeatUntilStatement.sourcePosition(), repeatUntilStatement.condition(), repeatUntilStatement.body());
                } catch (SemanticException e) {
                    errors.add(e);
                }
            }
            case Statement.RepeatWhileStatement repeatWhileStatement -> {
                try {
                    typeCheckLoop(
                            repeatWhileStatement.sourcePosition(), repeatWhileStatement.condition(), repeatWhileStatement.body());
                } catch (SemanticException e) {
                    errors.add(e);
                }
            }
            case Statement.StatementBlock statementBlock -> {
                for (Statement stmt : statementBlock.statements()) {
                    typecheck(stmt);
                }
            }
            case Statement.WhileStatement whileStatement -> {
                try {
                    typeCheckLoop(whileStatement.sourcePosition(), whileStatement.condition(), whileStatement.body());
                } catch (SemanticException e) {
                    errors.add(e);
                }
            }
            case Statement.NoopStatement _ -> { }
        }
    }

    private void typecheck(Expression expression) throws SemanticException {
        switch (expression) {
            case Expression.BinaryOp binOp -> {
                Type opT = terms.lookup(binOp.operator());

                typecheck(binOp.leftOperand());
                typecheck(binOp.rightOperand());

                // I special-case the equality operations because they are the ONLY polymorphic functions and I do not have enough
                //  time to implement full polymorphism
                if (binOp.operator().equals("=") || binOp.operator().equals("\\=")) {
                    // just make sure that left and right operands are the same type
                    Type lT = binOp.leftOperand().getType().baseType();
                    Type rT = binOp.leftOperand().getType().baseType();
                    if (!lT.equals(rT)) {
                        throw new SemanticException.TypeError(binOp.sourcePosition(), lT, rT);
                    }

                    binOp.setType(BOOL_TYPE);
                    return;
                }

                if (!(opT instanceof PrimType.FuncType(List<Type> argTypes, Type returnType))) {
                    throw new SemanticException.TypeError(binOp.sourcePosition(), opT, "function");
                }

                // left-operand type
                Type lT = binOp.leftOperand().getType().baseType();
                // expected left-operand type
                @SuppressWarnings("SequencedCollectionMethodCanBeUsed") Type exLT = argTypes.get(0);
                if (!(lT.equals(exLT))) {
                    throw new SemanticException.TypeError(binOp.leftOperand().sourcePosition(), lT, exLT);
                }

                // right-operand type
                Type rT = binOp.leftOperand().getType().baseType();
                // expected right-operand type
                @SuppressWarnings("SequencedCollectionMethodCanBeUsed") Type exRT = argTypes.get(0);
                if (!(rT.equals(exRT))) {
                    throw new SemanticException.TypeError(binOp.rightOperand().sourcePosition(), rT, exRT);
                }

                binOp.setType(returnType);
            }
            case Expression.FunCall funCall -> {
                Type fT = terms.lookup(funCall.func().name());

                if (!(fT instanceof PrimType.FuncType(List<Type> paramTypes, Type returnType))) {
                    throw new SemanticException.TypeError(funCall.sourcePosition(), fT, "function");
                }

                int argCount = funCall.arguments().size();
                int funArity = paramTypes.size();
                if (argCount != funArity) {
                    throw new SemanticException.ArityMismatch(funCall.sourcePosition(), funArity, argCount);
                }

                for (int i = 0; i < funCall.arguments().size(); i++) {
                    Argument arg = funCall.arguments().get(i);
                    Type exT = paramTypes.get(i).baseType();

                    annotateArgument(arg);

                    Type aT = arg.getType().baseType();
                    if (!(aT.equals(exT))) {
                        throw new SemanticException.TypeError(arg.sourcePosition(), aT, exT);
                    }
                }

                funCall.setType(returnType);
            }
            case Expression.Identifier identifier -> typecheck(identifier);
            case Expression.IfExpression ifExpression -> {
                typecheck(ifExpression.condition());

                Type cT = ifExpression.condition().getType().baseType();
                if (!(cT instanceof PrimType.BoolType)) {
                    throw new SemanticException.TypeError(ifExpression.sourcePosition(), cT, BOOL_TYPE);
                }

                typecheck(ifExpression.consequent());
                typecheck(ifExpression.alternative());

                // ensure the two branches are equal types
                Type lT = ifExpression.consequent().getType().baseType();
                Type rT = ifExpression.alternative().getType().baseType();
                if (!(rT.equals(lT))) {
                    throw new SemanticException.TypeError(ifExpression.sourcePosition(), rT, lT);
                }

                ifExpression.setType(lT);
            }
            case Expression.LetExpression letExpression -> {
                terms.enterNewScope(null);
                types.enterNewScope(null);

                for (Declaration declaration : letExpression.declarations()) {
                    bindDeclaration(declaration);
                }
                typecheck(letExpression.expression());

                terms.exitScope();
                types.exitScope();

                letExpression.setType(letExpression.expression().getType().baseType());
            }
            case Expression.LitArray litArray -> {
                Expression first = litArray.elements().getFirst();
                typecheck(first);

                // expected type for the rest of the elements
                Type exT = first.getType().baseType();
                for (Expression element : litArray.elements()) {
                    typecheck(element);
                    Type eT = element.getType().baseType();
                    if (!eT.equals(exT)) {
                        throw new SemanticException.TypeError(element.sourcePosition(), exT, eT);
                    }
                }

                litArray.setType(new ArrayType(litArray.elements().size(), exT));
            }
            case Expression.LitBool litBool -> litBool.setType(BOOL_TYPE);
            case Expression.LitChar litChar -> litChar.setType(CHAR_TYPE);
            case Expression.LitInt litInt -> litInt.setType(INT_TYPE);
            case Expression.LitRecord litRecord -> {
                List<RecordType.FieldType> fieldTypes = new ArrayList<>();
                for (Expression.LitRecord.RecordField field : litRecord.fields()) {
                    typecheck(field.value());
                    Type fT = field.value().getType();
                    fieldTypes.add(new RecordType.FieldType(field.name(), fT));
                }

                litRecord.setType(new RecordType(fieldTypes));
            }
            case Expression.SequenceExpression sequenceExpression -> {
                typecheck(sequenceExpression.statement());
                typecheck(sequenceExpression.expression());
                sequenceExpression.setType(sequenceExpression.expression().getType().baseType());
            }
            case Expression.UnaryOp unaryOp -> {
                Type opT = terms.lookup(unaryOp.operator());

                typecheck(unaryOp.operand());

                if (!(opT instanceof PrimType.FuncType(List<Type> argTypes, Type returnType))) {
                    throw new SemanticException.TypeError(unaryOp.sourcePosition(), opT, "function");
                }

                // operand type
                Type t = unaryOp.operand().getType().baseType();
                // expected operand type
                @SuppressWarnings("SequencedCollectionMethodCanBeUsed") Type exT = argTypes.get(0);
                if (!(t.equals(exT))) {
                    throw new SemanticException.TypeError(unaryOp.operand().sourcePosition(), t, exT);
                }

                unaryOp.setType(returnType);
            }
        }

        if (expression.getType() == null) {
            System.out.println(expression);
        }
        if (expression.getType().baseType() instanceof PrimType.FuncType) {
            throw new SemanticException.FunctionResult(expression.sourcePosition(), expression);
        }
    }

    private void typecheck(Expression.Identifier identifier) throws SemanticException {
        switch (identifier) {
            case Expression.Identifier.ArraySubscript arraySubscript -> {
                // arraySubscript(array : [e], subscript : Integer) : e

                typecheck(arraySubscript.array());
                typecheck(arraySubscript.subscript());

                Type aT = arraySubscript.array().getType().baseType();
                if (!(aT instanceof Type.ArrayType arrayType)) {
                    throw new SemanticException.TypeError(arraySubscript.sourcePosition(), aT, "array");
                }

                Type sT = arraySubscript.subscript().getType().baseType();
                if (!(sT instanceof Type.PrimType.IntType)) {
                    throw new SemanticException.TypeError(arraySubscript.sourcePosition(), sT, INT_TYPE);
                }

                // arr[0] has type arr.elementType if arr is not a ref
                // type RefOf(arr.elementType) if arr is a RefOf(refType)
                Type elemT = arrayType.elementType();
                Type eT = arraySubscript.array().getType().isRef() ? new Type.RefOf(elemT) : elemT;

                arraySubscript.setType(eT);
            }
            case Expression.Identifier.BasicIdentifier basicIdentifier -> {
                try {
                    basicIdentifier.setType(terms.lookup(basicIdentifier.name()));
                } catch (NoSuchElementException e) {
                    // even though TypeCheck runs after SemanticAnalyzer, we may still have undeclared uses, because
                    // SemanticAnalyzer does not check if field accesses of a record are valid (it doesn't have the necessary
                    // type information)
                    throw new SemanticException.UndeclaredUse(basicIdentifier.sourcePosition(), basicIdentifier);
                }
            }
            case Expression.Identifier.RecordAccess recordAccess -> {
                // recordAccess(record : {... field : t ...}, field : _) : t

                typecheck(recordAccess.record());

                Type rT = recordAccess.record().getType().baseType();
                if (!(rT instanceof RecordType(List<RecordType.FieldType> fieldTypes))) {
                    throw new SemanticException.TypeError(recordAccess.sourcePosition(), rT, "record");
                }

                terms.enterNewScope(null);

                for (Type.RecordType.FieldType fieldType : fieldTypes) {
                    terms.add(fieldType.fieldName(), fieldType.fieldType());
                }

                typecheck(recordAccess.field());

                terms.exitScope();

                Type fT = recordAccess.field().getType().baseType();
                recordAccess.setType(fT);
            }
        }
    }

    private void annotateArgument(final Argument argument) throws SemanticException {
        Type aT = switch (argument) {
            case Argument.FuncArgument funcArgument -> terms.lookup(funcArgument.func().name());
            case Argument.VarArgument varArgument -> {
                typecheck(varArgument.var());
                yield varArgument.var().getType();
            }
            case Expression expression -> {
                typecheck(expression);
                yield expression.getType();
            }
        };

        argument.setType(aT);
    }

    private void annotateParameter(final Parameter parameter) throws SemanticException {
        switch (parameter) {
            case Parameter.FuncParameter funcParameter -> {
                List<Type> paramTypes = new ArrayList<>();
                for (Parameter innerParam : funcParameter.parameters()) {
                    annotateParameter(innerParam);
                    paramTypes.add(innerParam.getType());
                }

                Type rT = resolveType(funcParameter.declaredReturnType());

                parameter.setType(new PrimType.FuncType(paramTypes, rT));
            }
            case Parameter.ValueParameter valueParameter -> {
                Type pT = resolveType(valueParameter.declaredType());
                parameter.setType(pT);
            }
            case Parameter.VarParameter varParameter -> {
                Type pT = resolveType(varParameter.declaredType());
                parameter.setType(new RefOf(pT));
            }
        }
    }

    private void bindDeclaration(final Declaration declaration) throws SemanticException {
        switch (declaration) {
            case Declaration.ConstDeclaration constDeclaration -> {
                // we cant really catch a semantic exception here, since we don't have even a declared type to bind this
                // constant to and continue typechecking with
                typecheck(constDeclaration.value());
                terms.add(constDeclaration.name(), constDeclaration.value().getType().baseType());
            }
            case Declaration.FuncDeclaration funcDeclaration -> {
                // type check and annotate all the parameters
                List<Type> paramTypes = new ArrayList<>();
                for (Parameter parameter : funcDeclaration.parameters()) {
                    annotateParameter(parameter);
                    paramTypes.add(parameter.getType().baseType());
                }

                // optimistically bind the function name to the (resolved) type that it is declared to be
                Type declaredReturnType = resolveType(funcDeclaration.returnTypeSig());
                terms.add(funcDeclaration.name(), new PrimType.FuncType(paramTypes, declaredReturnType));

                try {
                    terms.enterNewScope(null);
                    types.enterNewScope(null);

                    for (Parameter parameter : funcDeclaration.parameters()) {
                        terms.add(parameter.name(), parameter.getType().baseType());
                    }

                    typecheck(funcDeclaration.expression());

                    // if expressions inferred type does not match our (optimistic) assumption
                    Type eT = funcDeclaration.expression().getType().baseType();
                    if (!eT.equals(declaredReturnType)) {
                        throw new SemanticException.TypeError(funcDeclaration.sourcePosition(), declaredReturnType, eT);
                    }
                } catch (SemanticException e) {
                    errors.add(e);
                    // no need to rethrow, we can continue processing other declarations
                } finally {
                    // treat this function as having the (resolved) type that it is declared to be
                    types.exitScope();
                    terms.exitScope();
                }
            }
            case Declaration.ProcDeclaration procDeclaration -> {
                // typecheck and annotate all the parameters
                List<Type> paramTypes = new ArrayList<>();
                for (Parameter parameter : procDeclaration.parameters()) {
                    annotateParameter(parameter);
                    paramTypes.add(parameter.getType());
                }

                // optimistically bind the procedure name to the (resolved) type that it is declared to be
                terms.add(procDeclaration.name(), new PrimType.FuncType(paramTypes, VOID_TYPE));

                terms.enterNewScope(null);
                types.enterNewScope(null);

                for (Parameter parameter : procDeclaration.parameters()) {
                    terms.add(parameter.name(), parameter.getType());
                }

                // since its a statement, it knows how to catch and resume typechecking by itself
                typecheck(procDeclaration.statement());

                types.exitScope();
                terms.exitScope();

                // we can continue processing other declarations, and treat this procedure as having the (resolved) type
                // that it is declared to be
            }
            case Declaration.TypeDeclaration typeDeclaration -> types.add(
                    typeDeclaration.name(), resolveType(typeDeclaration.typeSig()));
            case Declaration.VarDeclaration varDeclaration -> {
                Type vT = resolveType(varDeclaration.declaredType());
                terms.add(varDeclaration.name(), vT);
                varDeclaration.setType(vT);
            }
        }
    }

    private void typeCheckLoop(SourcePosition sourcePos, Expression condition, Statement body) throws SemanticException {
        typecheck(condition);

        Type cT = condition.getType().baseType();
        if (!(cT instanceof PrimType.BoolType)) {
            throw new SemanticException.TypeError(sourcePos, cT, BOOL_TYPE);
        }

        typecheck(body);
    }

    private Type resolveType(final TypeSig typeSig) throws SemanticException {
        return switch (typeSig) {
            case TypeSig.ArrayTypeSig(int size, TypeSig elementTypeSig) -> new Type.ArrayType(size, resolveType(elementTypeSig));
            case TypeSig.RecordTypeSig recordType -> {
                List<Type.RecordType.FieldType> resolvedFieldTypes = new ArrayList<>();
                Set<String> seenFieldNames = new HashSet<>();
                for (TypeSig.RecordTypeSig.FieldType field : recordType.fieldTypes()) {
                    String fieldName = field.fieldName();

                    if (seenFieldNames.contains(fieldName)) {
                        throw new SemanticException.DuplicateRecordTypeField(field);
                    }

                    seenFieldNames.add(fieldName);
                    Type.RecordType.FieldType resolvedField = new Type.RecordType.FieldType(
                            fieldName, resolveType(field.fieldTypeSig()));
                    resolvedFieldTypes.add(resolvedField);
                }

                // canonicalize field types
                resolvedFieldTypes.sort(Comparator.comparing(RecordType.FieldType::fieldName));
                yield new Type.RecordType(resolvedFieldTypes);
            }
            case TypeSig.BasicTypeSig basicType -> {
                try {
                    yield types.lookup(basicType.name());
                } catch (NoSuchElementException _) {
                    throw new SemanticException.UndeclaredUse(basicType);
                }
            }
            case TypeSig.Void _ -> VOID_TYPE;
        };
    }

}
