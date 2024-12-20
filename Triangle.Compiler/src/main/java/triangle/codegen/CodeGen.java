package triangle.codegen;

import triangle.abstractMachine.Machine;
import triangle.abstractMachine.Primitive;
import triangle.abstractMachine.Register;
import triangle.codegen.CodeGen.Callable.PrimitiveCallable;
import triangle.codegen.CodeGen.Callable.StaticCallable;
import triangle.codegen.CodeGen.Callable.DynamicCallable;
import triangle.repr.Argument;
import triangle.repr.Declaration;
import triangle.repr.Expression;
import triangle.repr.Instruction;
import triangle.repr.Instruction.*;
import triangle.repr.Parameter;
import triangle.repr.Statement;
import triangle.repr.Type;
import triangle.util.StdEnv;
import triangle.util.SymbolTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class CodeGen {

    static final         Map<String, Callable> BUILTINS                 = new HashMap<>();
    // contains all compiler generated operations
    private static final List<Instruction> COMPILER_GENERATED_BLOCK = new ArrayList<>();
    // the label number immediately succeeding the last label number used for compiler generated operations,
    // i.e., the label number from which user code labels should start
    private static       int               firstLabel               = 0;

    static {
        StdEnv.PRIMITIVES.forEach((k, v) -> BUILTINS.put(k, new PrimitiveCallable(v)));

        // Compiler generated operations
        // |
        LABEL bar = new LABEL(firstLabel++);
        COMPILER_GENERATED_BLOCK.addAll(List.of(
                bar,
                new LOAD(Machine.integerSize, new Address(Register.LB, Machine.integerSize * -1)),
                new LOADL(100), new CALL_PRIM(Primitive.MULT),
                new RETURN(Machine.integerSize, 1)
        ));
        BUILTINS.put("|", new StaticCallable(bar));
    }

    static Register getDisplayRegister(final int depth) {
        return switch (depth) {
            case 0 -> Register.LB;
            case 1 -> Register.L1;
            case 2 -> Register.L2;
            case 3 -> Register.L3;
            case 4 -> Register.L4;
            case 5 -> Register.L5;
            case 6 -> Register.L6;
            default -> throw new RuntimeException("static nesting-depth limit exceeded");
        };
    }

    private final SymbolTable<Callable, Void>   callables     = new SymbolTable<>(BUILTINS, null);
    private final SymbolTable<Integer, Integer> variables     = new SymbolTable<>(0);
    private final Supplier<LABEL>               labelSupplier = new Supplier<>() {
        private int i = firstLabel;

        @Override public LABEL get() {
            return new LABEL(i++);
        }
    };
    private final List<Instruction>             program       = new LinkedList<>();

    public List<Instruction> generateInstructions(Statement ast) {
        generate(ast);
        program.add(new HALT());
        program.addAll(COMPILER_GENERATED_BLOCK);
        return program;
    }

    private void generate(final Statement statement) {
        switch (statement) {
            case Statement.AssignStatement assignStatement -> {
                //  [expression]
                //  evaluateAddress(variable)
                //  STOREI varSize

                // evaluate expression
                generate(assignStatement.expression());
                // evaluate address of identifier and put on stack
                generateStore(assignStatement.identifier(), assignStatement.expression().getType().size());
            }
            case Statement.ExpressionStatement expressionStatement -> {
                //  [expression]
                //  POP 0 resultSize

                generate(expressionStatement.expression());
                // discard results of ExpressionStatements, since they are intended to be used only for side-effects
                int resultSize = expressionStatement.expression().getType().size();
                program.add(new POP(0, resultSize));
            }
            case Statement.IfStatement ifStatement -> {
                //  [condition]
                //  JUMPIF 0 altLabel
                //  [consequent]
                //  JUMP skipLabel
                // altLabel:
                //  [alternative]
                // skipLabel:

                LABEL altLabel = labelSupplier.get();
                LABEL skipLabel = labelSupplier.get();

                generate(ifStatement.condition());
                program.add(new JUMPIF_LABEL(Machine.falseRep, altLabel));
                ifStatement.consequent().ifPresent(this::generate);
                program.add(new JUMP_LABEL(skipLabel));
                program.add(altLabel);
                ifStatement.alternative().ifPresent(this::generate);
                program.add(skipLabel);
            }
            case Statement.LetStatement letStatement -> {
                //  allocateDeclarations(declarations)
                //  [statement]

                // as declarations are evaluated, they will expand the stack
                // store the current stack top so we know how much to POP when returning
                int savedStackOffset = variables.scopeLocalState();
                int newStackOffset = allocateDeclarations(letStatement.declarations());
                int totalAllocated = newStackOffset - savedStackOffset;

                // set the scope local state to the new stack top so that any let statements/expressions in let body know how
                // much to POP when returning
                variables.setScopeLocalState(newStackOffset);

                // let body is generated in the new scope containing all our definitions
                generate(letStatement.statement());
                program.add(new POP(0, totalAllocated));
            }
            case Statement.LoopWhileStatement loopWhileStatement -> {
                // loopLabel:
                //  [loopBody]
                //  [condition]
                //  JUMPIF 0 skipLabel
                //  [doBody]
                //  JUMP loopLabel
                // skipLabel:

                LABEL loopLabel = labelSupplier.get();
                LABEL skipLabel = labelSupplier.get();

                program.add(loopLabel);
                generate(loopWhileStatement.loopBody());
                generate(loopWhileStatement.condition());
                program.add(new JUMPIF_LABEL(Machine.falseRep, skipLabel));
                generate(loopWhileStatement.doBody());
                program.add(new JUMP_LABEL(loopLabel));
                program.add(skipLabel);
            }
            case Statement.NoopStatement _ -> { }
            case Statement.RepeatUntilStatement repeatUntilStatement -> {
                LABEL loopLabel = labelSupplier.get();
                // loopLabel:
                //  [body]
                //  [condition]
                //  JUMPIF 0 loopLabel

                program.add(loopLabel);
                generate(repeatUntilStatement.body());
                generate(repeatUntilStatement.condition());
                program.add(new JUMPIF_LABEL(Machine.falseRep, loopLabel));
            }
            case Statement.RepeatWhileStatement repeatWhileStatement -> {
                LABEL loopLabel = labelSupplier.get();
                // loopLabel:
                //  [body]
                //  [condition]
                //  JUMPIF 1 loopLabel

                program.add(loopLabel);
                generate(repeatWhileStatement.body());
                generate(repeatWhileStatement.condition());
                program.add(new JUMPIF_LABEL(Machine.trueRep, loopLabel));
            }
            case Statement.StatementBlock statementBlock -> {
                //  [statements(1)]
                //  [statements(2)]
                //  ...
                //  [statements(n)]

                for (Statement aStatement : statementBlock.statements()) {
                    generate(aStatement);
                }
            }
            case Statement.WhileStatement whileStatement -> {
                // loopLabel:
                //  [condition]
                //  JUMPIF 0 skipLabel
                //  [body]
                //  JUMP loopLabel
                // skipLabel:

                LABEL loopLabel = labelSupplier.get();
                LABEL skipLabel = labelSupplier.get();

                program.add(loopLabel);
                generate(whileStatement.condition());
                program.add(new JUMPIF_LABEL(Machine.falseRep, skipLabel));
                generate(whileStatement.body());
                program.add(new JUMP_LABEL(loopLabel));
                program.add(skipLabel);
            }
        }
    }

    private void generate(Expression expression) {
        switch (expression) {
            //  generateCall(op, [lOperand, rOperand])
            case Expression.BinaryOp bop -> generateCall(bop.operator(), List.of(bop.leftOperand(), bop.rightOperand()));
            // generateCall(func, func.arguments)
            case Expression.FunCall funCall -> generateCall(funCall.func().name(), funCall.arguments());
            // fetch the value and leave on stack
            case Expression.Identifier identifier -> generateFetch(identifier, identifier.getType().baseType().size());
            case Expression.IfExpression ifExpression -> {
                //  [condition]
                //  JUMPIF 0 altLabel
                //  [consequent]
                //  JUMP skipLabel
                // alternateLabel:
                //  [alternative]
                // skipLabel:

                LABEL altLabel = labelSupplier.get();
                LABEL skipLabel = labelSupplier.get();

                generate(ifExpression.condition());
                program.add(new JUMPIF_LABEL(Machine.falseRep, altLabel));
                generate(ifExpression.consequent());
                program.add(new JUMP_LABEL(skipLabel));
                program.add(altLabel);
                generate(ifExpression.alternative());
                program.add(skipLabel);
            }
            case Expression.LetExpression letExpression -> {
                // see generate(LetStatement) for comments

                int savedStackOffset = variables.scopeLocalState();
                int newStackOffset = allocateDeclarations(letExpression.declarations());
                int totalAllocated = newStackOffset - savedStackOffset;

                variables.setScopeLocalState(newStackOffset);

                generate(letExpression.expression());

                int resultSize = letExpression.expression().getType().size();
                program.add(new POP(resultSize, totalAllocated));
            }
            case Expression.LitArray litArray -> {
                // generate all the values and leave them on the stack contiguously
                for (Expression e : litArray.elements()) {
                    generate(e);
                }
            }
            case Expression.LitBool litBool -> program.add(new LOADL(litBool.value() ? Machine.trueRep : Machine.falseRep));
            case Expression.LitChar litChar -> program.add(new LOADL(litChar.value()));
            case Expression.LitInt litInt -> program.add(new LOADL(litInt.value()));
            case Expression.LitRecord litRecord -> {
                for (Expression.LitRecord.RecordField field : litRecord.fields()) {
                    generate(field.value());
                }
            }
            // generateCall(op, [operand])
            case Expression.UnaryOp unaryOp -> generateCall(unaryOp.operator(), List.of(unaryOp.operand()));
            // [statement]
            // [expression]
            case Expression.SequenceExpression sequenceExpression -> {

                generate(sequenceExpression.statement());
                generate(sequenceExpression.expression());
            }
        }
    }

    // allocate space for each declaration, pushing to the stack as needed
    // when procedures/functions are encountered, the code for the function is generated in-place but a jump-ahead is included
    // so that it is not executed immediately; a label is associated with the entry point of the newly defined function and
    // added to funcAddresses
    private int allocateDeclarations(final List<Declaration> declarations) {
        int stackOffset = variables.scopeLocalState();

        for (Declaration declaration : declarations) {
            switch (declaration) {
                case Declaration.ConstDeclaration constDeclaration -> {
                    // generate value -> declaration name with stack offset -> bump stack offset by size of declared value
                    generate(constDeclaration.value());
                    variables.add(constDeclaration.name(), stackOffset);
                    stackOffset += constDeclaration.value().getType().size();
                }
                case Declaration.FuncDeclaration funcDeclaration -> {
                    //  JUMP skipLabel
                    // funcLabel:
                    //  [functionBody]
                    //  RETURN returnValueSize paramsSize
                    // skipLabel:

                    //noinspection DuplicatedCode
                    LABEL skipLabel = labelSupplier.get();
                    LABEL funcLabel = labelSupplier.get();

                    program.add(new JUMP_LABEL(skipLabel));
                    program.add(funcLabel);

                    // the function currently being declared be added to funcAddresses before we process its declaration, so as
                    // to allow recursion
                    callables.add(funcDeclaration.name(), new StaticCallable(funcLabel));

                    // new scope for local vars and functions, since we are in a func declaration, the scope local state (i.e.,
                    // the initial stack offset) must be Machine.linkDataSize -- for static link, dynamic link, return address
                    variables.enterNewScope(Machine.linkDataSize);
                    callables.enterNewScope(null);

                    // we need the total amount of space taken by the function to know what to use for the RETURN call
                    int paramsSize = addParametersToScope(funcDeclaration.parameters());

                    generate(funcDeclaration.expression());
                    program.add(new RETURN(funcDeclaration.expression().getType().size(), paramsSize));

                    variables.exitScope();
                    callables.exitScope();

                    program.add(skipLabel);
                }
                // nothing to do for type declarations
                case Declaration.TypeDeclaration _ -> { }
                case Declaration.VarDeclaration varDeclaration -> {
                    program.add(new PUSH(varDeclaration.getType().size()));
                    variables.add(varDeclaration.name(), stackOffset);
                    stackOffset += varDeclaration.getType().size();
                }
                case Declaration.ProcDeclaration procDeclaration -> {
                    //  JUMP skipLabel
                    // procLabel:
                    //  [procBody]
                    //  RETURN 0 paramsSize
                    // skipLabel:

                    //noinspection DuplicatedCode
                    LABEL skipLabel = labelSupplier.get();
                    LABEL procLabel = labelSupplier.get();

                    program.add(new JUMP_LABEL(skipLabel));
                    program.add(procLabel);

                    // the proc currently being declared must be added to funcAddresses before we process its declaration, so
                    // as to allow recursion
                    callables.add(procDeclaration.name(), new StaticCallable(procLabel));

                    // new scope for local vars and functions, since we are in a proc declaration, the scope local state (i.e.,
                    // the initial stack offset) must be Machine.linkDataSize -- for static link, dynamic link, return address
                    variables.enterNewScope(Machine.linkDataSize);
                    callables.enterNewScope(null);

                    // we need the total amount of space that will be taken by the parameters to know how to RETURN
                    int paramsSize = addParametersToScope(procDeclaration.parameters());

                    generate(procDeclaration.statement());
                    program.add(new RETURN(0, paramsSize));

                    variables.exitScope();
                    callables.exitScope();

                    program.add(skipLabel);
                }
            }
        }

        return stackOffset;
    }

    // associates each parameter with its location relative to current frame, returns no words that will be  allocated for
    // parameter when the corresponding function is called
    private int addParametersToScope(List<Parameter> parameters) {
        int paramOffset = 0;
        for (Parameter parameter : parameters.reversed()) {
            paramOffset -= parameter.getType().size();
            switch (parameter) {
                // static link and code address to be on stack
                case Parameter.FuncParameter _ -> callables.add(parameter.name(), new DynamicCallable(paramOffset));
                case Parameter.ValueParameter _, Parameter.VarParameter _ -> variables.add(parameter.name(), paramOffset);
            }
        }

        return paramOffset * -1;
    }

    private void generateCall(final String funcName, final List<Argument> arguments) {
        //  [argument(0)]
        //  [argument(1)]
        //  ...
        //  [argument(n)]

        for (Argument argument : arguments) {
            loadArgument(argument);
        }

        // we have to special case chr and ord; TAM cannot provide primitives for these because it doesn't know our char encoding
        if (funcName.equals("chr") || funcName.equals("ord")) {
            // the argument was generated and already on the stack, so just return; SemanticAnalyzer ensures that nothing can
            // go wrong if we just leave the value as is, because our char encoding translates without changes to
            // our integer encoding
            return;
        }

        // eq and neq take an additional size argument that is not visible to the user (i.e., it does not and should not show
        // up on semantic analysis and the user must not have to add the size argument manually)
        if (funcName.equals("=") || funcName.equals("\\=")) {
            // push size on the stack
            program.add(new LOADL(arguments.getFirst().getType().baseType().size()));
        }

        SymbolTable<Callable, Void>.DepthLookup lookup = callables.lookupWithDepth(funcName);
        switch (lookup.t()) {
            case DynamicCallable(int stackOffset) -> {
                //  LOAD addressSize stackOffset[nonLocalsLink]          <- load static link
                //  LOAD addressSize (stackOffset + 1)[nonLocalsLink]    <- load code addr
                //  CALLI

                Register nonLocalsLink = getDisplayRegister(lookup.depth());
                program.add(new LOAD(Machine.addressSize, new Address(nonLocalsLink, stackOffset)));
                program.add(new LOAD(Machine.addressSize, new Address(nonLocalsLink, stackOffset + 1)));
                program.add(new CALLI());
            }
            case PrimitiveCallable(Primitive primitive) -> program.add(new CALL_PRIM(primitive));
            case StaticCallable(LABEL label) -> program.add(new CALL_LABEL(getDisplayRegister(lookup.depth()), label));
        }
    }

    private void loadArgument(final Argument argument) {
        switch (argument) {
            case Argument.FuncArgument funcArgument -> {
                // put a closure -- static link + code address -- onto the stack

                SymbolTable<Callable, Void>.DepthLookup lookup = callables.lookupWithDepth(funcArgument.func().name());
                Register nonLocalsLink = getDisplayRegister(lookup.depth());

                switch (lookup.t()) {
                    case Callable.DynamicCallable(int stackOffset) -> {
                        //  LOADA stackOffset[nonLocalsLink]         <- load static link
                        //  LOADI Machine.addressSize
                        //  LOADA (stackOffset + 1)[nonLocalsLink]   <- load code addr
                        //  LOADI Machine.addressSize

                        // closure (i.e, func and proc arguments) are addresses to addresses, so dereference
                        program.add(new LOADA(new Address(nonLocalsLink, stackOffset)));
                        program.add(new LOADI(Machine.addressSize));
                        program.add(new LOADA(new Address(nonLocalsLink, stackOffset + 1)));
                        program.add(new LOADI(Machine.addressSize));
                    }
                    case Callable.PrimitiveCallable(Primitive primitive) -> {
                        //  LOADA 0[LB]
                        //  LOADA primitive.ordinal()[PB]

                        // use whatever as static link for primitives -- 0[LB] here
                        program.add(new LOADA(new Address(Register.LB, 0)));
                        program.add(new LOADA(new Address(Register.PB, primitive.ordinal())));
                    }
                    case Callable.StaticCallable(LABEL label) -> {
                        //  LOADA 0[nonLocalsLink]
                        //  LOADA label

                        program.add(new LOADA(new Address(nonLocalsLink, 0)));
                        program.add(new LOADA_LABEL(label));
                    }
                }
            }
            // load address of var argument
            // if the argument provided is already an address, then dont dereference it here needlessly
            case Argument.VarArgument varArgument -> generateRuntimeLocation(varArgument.var(), varArgument.getType().isRef());
            // just evaluate expr and leave it on stack
            case Expression expressionArg -> generate(expressionArg);
        }
    }

    // generates instructions to push the value in `identifier`'s runtime location to the stack
    private void generateFetch(Expression.Identifier identifier, int size) {
        switch (identifier) {
            case Expression.Identifier.ArraySubscript arraySubscript -> {
                generateRuntimeLocation(arraySubscript, true);
                program.add(new LOADI(size));
            }
            case Expression.Identifier.BasicIdentifier basicIdentifier -> {
                Address address = lookupAddress(basicIdentifier);

                if (basicIdentifier.getType().isRef()) {
                    generateRuntimeLocation(identifier, true);
                    program.add(new LOADI(size));
                } else {
                    program.add(new LOAD(size, address));
                }
            }
            case Expression.Identifier.RecordAccess recordAccess -> {
                generateRuntimeLocation(recordAccess.record(), true);
                generateRecordAccess(recordAccess);
                program.add(new LOADI(size));
            }
        }
    }

    // generates instructions to pop the last `size` words of data and store it to the location that identifier will be found in
    // at runtime
    private void generateStore(Expression.Identifier identifier, int size) {
        switch (identifier) {
            case Expression.Identifier.ArraySubscript arraySubscript -> {
                generateRuntimeLocation(arraySubscript, true);
                program.add(new STOREI(size));
            }
            case Expression.Identifier.BasicIdentifier basicIdentifier -> {
                Address address = lookupAddress(basicIdentifier);

                if (basicIdentifier.getType().isRef()) {
                    generateRuntimeLocation(identifier, true);
                    program.add(new STOREI(size));
                } else {
                    program.add(new STORE(size, address));
                }
            }
            case Expression.Identifier.RecordAccess recordAccess -> {
                generateRuntimeLocation(recordAccess, true);
                program.add(new STOREI(size));
            }
        }
    }

    // generates instructions to push the address of `identifier`s runtime location to the stack; if dereferencing is true, the
    // address is dereferenced iff it is a RefOf
    private void generateRuntimeLocation(Expression.Identifier identifier, boolean dereferencing) {
        switch (identifier) {
            case Expression.Identifier.ArraySubscript arraySubscript -> {
                // push address of array base on stack
                generateRuntimeLocation(arraySubscript.array(), dereferencing);
                // evaluate subscript
                generate(arraySubscript.subscript());
                // LOADL arrayElementSize
                // push element size on stack
                Type.ArrayType aT = (Type.ArrayType) arraySubscript.array().getType().baseType();
                program.add(new LOADL(aT.elementType().size()));
                // CALL Primitive.MULT, to get offset
                program.add(new CALL_PRIM(Primitive.MULT));
                // CALL Primitive.ADD, to add offset to address of root
                program.add(new CALL_PRIM(Primitive.ADD));
            }
            case Expression.Identifier.BasicIdentifier basicIdentifier -> {
                Address address = lookupAddress(basicIdentifier);
                program.add(new LOADA(address));

                // if its a identifier is already a reference, then "dereference" it
                if (dereferencing && basicIdentifier.getType().isRef()) {
                    program.add(new LOADI(Machine.addressSize));
                }
            }
            case Expression.Identifier.RecordAccess recordAccess -> {
                generateRuntimeLocation(recordAccess.record(), dereferencing);
                generateRecordAccess(recordAccess);
            }
        }
    }

    private void generateRecordAccess(Expression.Identifier.RecordAccess recordAccess) {
        // first generate instructions to access the base of the record; this can be any arbitrary identifier
        switch (recordAccess.record()) {
            case Expression.Identifier.ArraySubscript arraySubscript -> {
                Type.ArrayType arrayType = (Type.ArrayType) arraySubscript.array().getType().baseType();
                int elemSize = arrayType.elementType().baseType().size();

                generate(arraySubscript.subscript());
                program.add(new LOADL(elemSize));
                program.add(new CALL_PRIM(Primitive.MULT));
                program.add(new CALL_PRIM(Primitive.ADD));
            }
            case Expression.Identifier.BasicIdentifier _ -> { }
            // our parser guarantees this
            case Expression.Identifier.RecordAccess _ -> throw new RuntimeException("record access as left-side of record");
        }

        // calculate the offset from base address of root, to the place where the field we want to access starts
        // this assumes that order of field types corresponds to order in which field values are located contiguously
        // in memory
        int offset = 0;
        var fieldTypes = ((Type.RecordType) recordAccess.record().getType().baseType()).fieldTypes();
        for (var fieldType : fieldTypes) {
            if (fieldType.fieldName().equals(recordAccess.field().root().name())) {
                break;
            }

            assert !(fieldType.fieldType().isRef());
            offset += fieldType.fieldType().size();
        }

        // bump address on the stack by `offset`, if needed
        if (offset > 0) {
            program.add(new LOADL(offset));
            program.add(new CALL_PRIM(Primitive.ADD));
        }

        // now, the top of the stack contains the base address for record.accessedField

        // depending on what kind of identifier the field is, we want to do a few different things
        switch (recordAccess.field()) {
            case Expression.Identifier.ArraySubscript arraySubscript -> {
                Type.ArrayType arrayType = (Type.ArrayType) arraySubscript.array().getType().baseType();
                int elemSize = arrayType.elementType().baseType().size();

                generate(arraySubscript.subscript());
                program.add(new LOADL(elemSize));
                program.add(new CALL_PRIM(Primitive.MULT));
                program.add(new CALL_PRIM(Primitive.ADD));
            }
            case Expression.Identifier.BasicIdentifier _ -> { }
            case Expression.Identifier.RecordAccess access -> generateRecordAccess(access);
        }
    }

    // given a basic identifier, gets the address it may be found in at runtime
    private Address lookupAddress(Expression.Identifier.BasicIdentifier basicIdentifier) {
        SymbolTable<Integer, Integer>.DepthLookup lookup = variables.lookupWithDepth(basicIdentifier.name());
        return new Address(getDisplayRegister(lookup.depth()), lookup.t());
    }

    // represents things that may be the target of CALL/CALLI instructions
    sealed interface Callable {

        // a callable whose location is known statically
        record StaticCallable(LABEL label) implements Callable { }

        // a callable whose closure may be found at the given stackOffset with static nesting depth decided elsewhere
        record DynamicCallable(int stackOffset) implements Callable { }

        // a primitive call
        record PrimitiveCallable(Primitive primitive) implements Callable { }

    }

}
