package org.batcher.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Generates a public, final implementation for a public {@code @BatchInputType} interface.
 * Implementations use the interface's binary name followed by {@code Impl}, including
 * {@code $} for nested interfaces, so they can be discovered by {@code Batcher.init}.
 * Generic/raw input types, sealed interfaces, checked exceptions, and primitive inputs
 * other than {@code int} for {@code BatchInput<Integer>} are not supported.
 */
@SupportedAnnotationTypes("org.batcher.BatchInputType")
public final class BatchInputProcessor extends AbstractProcessor {

    private static final String BATCH_INPUT = "org.batcher.BatchInput";
    private final Set<String> generated = new HashSet<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return true;
        }
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() != ElementKind.INTERFACE) {
                    error(element, "@BatchInputType is only supported on interfaces.");
                } else {
                    processInput((TypeElement) element);
                }
            }
        }
        return true;
    }

    private void processInput(TypeElement input) {
        Elements elements = processingEnv.getElementUtils();
        Types types = processingEnv.getTypeUtils();
        if (!input.getTypeParameters().isEmpty()) {
            error(input, "@BatchInputType interfaces must not be generic; bind BatchInput<T> to a concrete type.");
            return;
        }
        if (input.getModifiers().contains(Modifier.SEALED)) {
            error(input, "@BatchInputType does not support sealed interfaces.");
            return;
        }
        for (Element enclosing = input; enclosing instanceof TypeElement;
                enclosing = enclosing.getEnclosingElement()) {
            if (!enclosing.getModifiers().contains(Modifier.PUBLIC)) {
                error(input, "@BatchInputType interfaces and all enclosing types must be public.");
                return;
            }
        }

        TypeElement contract = elements.getTypeElement(BATCH_INPUT);
        if (contract == null) {
            error(input, "Cannot resolve org.batcher.BatchInput; the standalone batching API must be on the classpath.");
            return;
        }
        DeclaredType inputType = (DeclaredType) input.asType();
        DeclaredType batchInput = findBatchInput(inputType, contract, new HashSet<>());
        if (batchInput == null) {
            error(input, "@BatchInputType interfaces must extend BatchInput<T> with a concrete element type.");
            return;
        }
        if (batchInput.getTypeArguments().size() != 1
                || !isResolved(batchInput.getTypeArguments().get(0))) {
            error(input, "BatchInput<T> must have a fully resolved element type; raw types and type variables are not supported.");
            return;
        }
        TypeMirror itemType = batchInput.getTypeArguments().get(0);

        List<Method> inputMethods = new ArrayList<>();
        for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(input))) {
            if (!method.getModifiers().contains(Modifier.ABSTRACT)) {
                continue;
            }
            ExecutableType memberType = (ExecutableType) types.asMemberOf(inputType, method);
            if (isClose(method, memberType)) {
                if (hasCheckedThrows(memberType)) {
                    error(method, "The close() contract must not declare checked exceptions.");
                    return;
                }
                continue;
            }
            // Unrelated parent interfaces can contribute the same abstract signature.
            boolean duplicate = inputMethods.stream().anyMatch(existing ->
                    existing.element().getSimpleName().contentEquals(method.getSimpleName())
                            && types.isSubsignature(existing.type(), memberType)
                            && types.isSubsignature(memberType, existing.type()));
            if (duplicate) {
                if (!validateMethod(method, memberType, itemType)) {
                    return;
                }
            } else {
                inputMethods.add(new Method(method, memberType));
            }
        }
        if (inputMethods.size() != 1) {
            error(input, "@BatchInputType requires exactly one abstract input method besides void close(); found "
                    + inputMethods.size() + ". Default and static methods are allowed.");
            return;
        }
        Method method = inputMethods.get(0);
        if (!validateMethod(method.element(), method.type(), itemType)) {
            return;
        }
        generate(input, itemType, method);
    }

    private DeclaredType findBatchInput(TypeMirror type, TypeElement contract, Set<String> visited) {
        if (type.getKind() != TypeKind.DECLARED || !visited.add(type.toString())) {
            return null;
        }
        DeclaredType declared = (DeclaredType) type;
        if (declared.asElement().equals(contract)) {
            return declared;
        }
        for (TypeMirror parent : processingEnv.getTypeUtils().directSupertypes(type)) {
            DeclaredType found = findBatchInput(parent, contract, visited);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean isResolved(TypeMirror type) {
        return switch (type.getKind()) {
            case ARRAY -> isResolved(((ArrayType) type).getComponentType());
            case WILDCARD -> {
                WildcardType wildcard = (WildcardType) type;
                yield (wildcard.getExtendsBound() == null || isResolved(wildcard.getExtendsBound()))
                        && (wildcard.getSuperBound() == null || isResolved(wildcard.getSuperBound()));
            }
            case DECLARED -> {
                DeclaredType declared = (DeclaredType) type;
                TypeElement element = (TypeElement) declared.asElement();
                boolean concrete = element.getTypeParameters().size() == declared.getTypeArguments().size();
                if (declared.getEnclosingType().getKind() != TypeKind.NONE) {
                    concrete &= isResolved(declared.getEnclosingType());
                }
                for (TypeMirror argument : declared.getTypeArguments()) {
                    concrete &= isResolved(argument);
                }
                yield concrete;
            }
            // Primitive components of a concrete array are valid reference input types.
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    private boolean isClose(ExecutableElement method, ExecutableType type) {
        return method.getSimpleName().contentEquals("close")
                && type.getParameterTypes().isEmpty()
                && type.getReturnType().getKind() == TypeKind.VOID
                && type.getTypeVariables().isEmpty();
    }

    private boolean validateMethod(ExecutableElement method, ExecutableType type, TypeMirror itemType) {
        if (!type.getTypeVariables().isEmpty()) {
            error(method, "The abstract input method must not be generic.");
            return false;
        }
        if (type.getReturnType().getKind() != TypeKind.VOID || type.getParameterTypes().size() != 1) {
            error(method, "The abstract input method must return void and take exactly one argument.");
            return false;
        }
        if (hasCheckedThrows(type)) {
            error(method, "The abstract input method must not declare checked exceptions.");
            return false;
        }
        TypeMirror argument = type.getParameterTypes().get(0);
        Types types = processingEnv.getTypeUtils();
        if (argument.getKind().isPrimitive()) {
            TypeMirror integer = processingEnv.getElementUtils().getTypeElement("java.lang.Integer").asType();
            if (argument.getKind() != TypeKind.INT) {
                error(method, "Unsupported primitive input type " + argument
                        + "; only int is supported, and only for BatchInput<Integer>.");
                return false;
            }
            if (!types.isSameType(itemType, integer)) {
                error(method, "Primitive int input requires BatchInput<Integer>, but the element type is " + itemType + ".");
                return false;
            }
        } else if (!types.isSameType(argument, itemType)) {
            error(method, "Input argument type " + argument + " must exactly match BatchInput element type " + itemType + ".");
            return false;
        }
        return true;
    }

    private boolean hasCheckedThrows(ExecutableType method) {
        Types types = processingEnv.getTypeUtils();
        Elements elements = processingEnv.getElementUtils();
        TypeMirror runtimeException = elements.getTypeElement("java.lang.RuntimeException").asType();
        TypeMirror error = elements.getTypeElement("java.lang.Error").asType();
        return method.getThrownTypes().stream().anyMatch(thrown ->
                !types.isSubtype(thrown, runtimeException) && !types.isSubtype(thrown, error));
    }

    private void generate(TypeElement input, TypeMirror itemType, Method method) {
        Elements elements = processingEnv.getElementUtils();
        String packageName = elements.getPackageOf(input).getQualifiedName().toString();
        String binaryName = elements.getBinaryName(input).toString();
        String qualifiedName = binaryName + "Impl";
        String simpleName = packageName.isEmpty() ? qualifiedName : qualifiedName.substring(packageName.length() + 1);
        if (!generated.add(qualifiedName)) {
            return;
        }
        if (elements.getTypeElement(qualifiedName) != null) {
            error(input, "Cannot generate " + qualifiedName + ": that type already exists. Implementations are named using the input's binary name + Impl.");
            return;
        }
        boolean primitive = method.type().getParameterTypes().get(0).getKind() == TypeKind.INT;
        String bufferType = primitive ? "org.batcher.Batcher.IntBuffer" : "org.batcher.Batcher.ObjectBuffer<" + itemType + ">";
        String argumentType = method.type().getParameterTypes().get(0).toString();
        try {
            JavaFileObject source = processingEnv.getFiler().createSourceFile(qualifiedName, input);
            try (Writer writer = source.openWriter()) {
                if (!packageName.isEmpty()) {
                    writer.write("package " + packageName + ";\n\n");
                }
                writer.write("public final class " + simpleName + " implements " + input.getQualifiedName() + " {\n"
                        + "    private final " + bufferType + " buffer;\n\n"
                        + "    public " + simpleName + "(org.batcher.BatchSettings settings,\n"
                        + "            java.util.function.Consumer<? super java.util.List<" + itemType + ">> consumer) {\n"
                        + "        this.buffer = new " + bufferType + "(settings, consumer);\n"
                        + "    }\n\n"
                        + "    @Override\n"
                        + "    public void " + method.element().getSimpleName() + "(" + argumentType + " value) {\n"
                        + "        buffer.add(value);\n"
                        + "    }\n\n"
                        + "    @Override\n"
                        + "    public void close() {\n"
                        + "        buffer.close();\n"
                        + "    }\n"
                        + "}\n");
            }
        } catch (IOException exception) {
            error(input, "Could not generate " + qualifiedName + ": " + exception.getMessage());
        }
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private record Method(ExecutableElement element, ExecutableType type) {
    }
}
