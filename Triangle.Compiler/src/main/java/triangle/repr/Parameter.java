package triangle.repr;

import java.util.List;

// TODO: maybe make Parameter a sealed class and move sourcePos and type to it?
sealed public interface Parameter extends Annotatable.Typeable, Annotatable.SourceLocatable
        permits Parameter.ValueParameter, Parameter.FuncParameter, Parameter.VarParameter {

    String name();

    final class ValueParameter implements Parameter {

        private final String         name;
        private final TypeSig        typeSig;
        private       SourcePosition sourcePos;
        private       Type           type;

        public ValueParameter(String name, TypeSig typeSig) {
            this.name = name;
            this.typeSig = typeSig;
        }

        @Override public String toString() {
            return "ValueParameter[" + ", " + "name=" + name + ", " + "typeSig=" + typeSig + ']';
        }

        @Override public Type getType() {
            return type;
        }

        @Override public void setType(Type type) {
            this.type = type;
        }

        @Override public void setSourcePosition(final SourcePosition sourcePos) {
            this.sourcePos = sourcePos;
        }

        @Override public SourcePosition sourcePosition() {
            return sourcePos;
        }

        @Override public String name() {
            return name;
        }

        public TypeSig declaredType() {
            return typeSig;
        }

    }

    final class VarParameter implements Parameter {

        private final String         name;
        private final TypeSig        typeSig;
        private       SourcePosition sourcePos;
        private       Type           type;

        public VarParameter(String name, TypeSig typeSig) {
            this.name = name;
            this.typeSig = typeSig;
        }

        @Override public String toString() {
            return "VarParameter[" + ", " + "name=" + name + ", " + "typeSig=" + type + ']';
        }

        @Override public Type getType() {
            return type;
        }

        @Override public void setType(Type type) {
            this.type = type;
        }

        @Override public void setSourcePosition(final SourcePosition sourcePos) {
            this.sourcePos = sourcePos;
        }

        @Override public SourcePosition sourcePosition() {
            return sourcePos;
        }

        @Override public String name() {
            return name;
        }

        public TypeSig declaredType() {
            return typeSig;
        }

    }

    final class FuncParameter implements Parameter {

        private final String          callable;
        private final List<Parameter> parameters;
        private final TypeSig         returnTypeSig;
        private       SourcePosition  sourcePos;
        private       Type            returnType;

        public FuncParameter(String callable, List<Parameter> parameters, TypeSig returnTypeSig) {
            this.callable = callable;
            this.parameters = parameters;
            this.returnTypeSig = returnTypeSig;
        }

        @Override public String name() {
            return callable;
        }

        @Override public String toString() {
            return "FuncParameter[" + ", " + "callable=" + callable + ", " + "parameters=" + parameters + ", " + "returnType=" +
                   returnType + ']';
        }

        @Override public Type getType() {
            return returnType;
        }

        @Override public void setType(Type returnType) {
            this.returnType = returnType;
        }

        @Override public void setSourcePosition(final SourcePosition sourcePos) {
            this.sourcePos = sourcePos;
        }

        @Override public SourcePosition sourcePosition() {
            return sourcePos;
        }

        public String callable() {
            return callable;
        }

        public List<Parameter> parameters() {
            return parameters;
        }

        public TypeSig declaredReturnType() {
            return returnTypeSig;
        }

    }

}
