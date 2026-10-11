package org.batcher.processor;

import java.io.IOException;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
class BatchInputProcessorTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesPrimitiveIntegerImplementation() throws Exception {
        Compilation result = compile(Map.of("sample.Numbers", input("Numbers", "Integer", "void append(int value);")));

        assertTrue(result.success(), result.diagnostics());
        String generated = result.source("sample/NumbersImpl.java");
        assertTrue(generated.contains("public final class NumbersImpl implements sample.Numbers"), generated);
        assertTrue(generated.contains("private final org.batcher.Batcher.IntBuffer buffer;"), generated);
        assertTrue(generated.contains("public NumbersImpl(org.batcher.BatchSettings settings,"), generated);
        assertTrue(generated.contains("Consumer<? super java.util.List<java.lang.Integer>>"), generated);
        assertTrue(generated.contains("public void append(int value)"), generated);
        assertTrue(generated.contains("buffer.add(value);"), generated);
        assertTrue(generated.contains("buffer.close();"), generated);
        assertTrue(Files.exists(result.classes().resolve("sample/NumbersImpl.class")));
    }

    @Test
    void generatesReferenceImplementationAndAllowsDefaultStaticAndUncheckedMethods() throws Exception {
        Compilation result = compile(Map.of("sample.Text", input("Text", "String", """
                void append(String value) throws IllegalArgumentException;
                default String description() { return "text"; }
                static int version() { return 1; }
                """)));

        assertTrue(result.success(), result.diagnostics());
        String generated = result.source("sample/TextImpl.java");
        assertTrue(generated.contains("org.batcher.Batcher.ObjectBuffer<java.lang.String>"), generated);
        assertTrue(generated.contains("public void append(java.lang.String value)"), generated);
    }

    @Test
    void boxedIntegerUsesObjectBuffer() throws Exception {
        Compilation result = compile(Map.of("sample.Numbers", input("Numbers", "Integer", "void append(Integer value);")));

        assertTrue(result.success(), result.diagnostics());
        assertTrue(result.source("sample/NumbersImpl.java").contains("org.batcher.Batcher.ObjectBuffer<java.lang.Integer>"));
    }

    @ParameterizedTest
    @MethodSource("wildcardPayloads")
    void supportsWildcardArgumentsInObjectPayloads(String payloadType) throws Exception {
        Compilation result = compile(Map.of("sample.Values",
                input("Values", payloadType, "void append(" + payloadType + " value);")));

        assertTrue(result.success(), result.diagnostics());
        assertTrue(result.source("sample/ValuesImpl.java").contains("org.batcher.Batcher.ObjectBuffer<"));
    }

    private static Stream<String> wildcardPayloads() {
        return Stream.of("java.util.List<?>", "java.util.List<? extends Number>",
                "java.util.Map<String, ? super Integer>", "java.util.List<? extends java.util.List<String>>");
    }

    @Test
    void resolvesInheritedGenericInputAndBatchInputThroughMultipleSupertypes() throws Exception {
        Compilation result = compile(Map.of("sample.Text", """
                package sample;
                interface Parent<T> extends org.batcher.BatchInput<T> { void append(T value); }
                interface Middle<U> extends Parent<java.util.List<U>> { }
                @org.batcher.BatchInputType
                public interface Text extends Middle<String> { }
                """));

        assertTrue(result.success(), result.diagnostics());
        String generated = result.source("sample/TextImpl.java");
        assertTrue(generated.contains("ObjectBuffer<java.util.List<java.lang.String>>"), generated);
        assertTrue(generated.contains("public void append(java.util.List<java.lang.String> value)"), generated);
    }

    @Test
    void collapsesEquivalentInheritedAbstractMethods() throws Exception {
        Compilation result = compile(Map.of("sample.Text", """
                package sample;
                interface Left<T> { void append(T value); }
                interface Right<T> { void append(T value); }
                @org.batcher.BatchInputType
                public interface Text extends org.batcher.BatchInput<String>, Left<String>, Right<String> { }
                """));

        assertTrue(result.success(), result.diagnostics());
        assertTrue(result.source("sample/TextImpl.java").contains("public void append(java.lang.String value)"));
    }

    @Test
    void honorsMostSpecificOverridesIncludingCloseAndInheritedDefaultMethods() throws Exception {
        Compilation result = compile(Map.of("sample.Text", """
                package sample;
                interface Parent<T> extends org.batcher.BatchInput<T> {
                    void append(T value) throws java.io.IOException;
                    void unused();
                }
                @org.batcher.BatchInputType
                public interface Text extends Parent<String> {
                    @Override void append(String value);
                    @Override default void unused() { }
                    @Override void close();
                }
                """));

        assertTrue(result.success(), result.diagnostics());
    }

    @Test
    void closeDefaultsDoNotReplaceGeneratedBufferCleanup() throws Exception {
        Compilation result = compile(Map.of("sample.Text", input("Text", "String", """
                void append(String value);
                @Override default void close() { }
                """)));

        assertTrue(result.success(), result.diagnostics());
        assertTrue(result.source("sample/TextImpl.java").contains("buffer.close();"));
    }

    @Test
    void supportsConcreteArraysAndVarargs() throws Exception {
        Compilation result = compile(Map.of("sample.Values", input("Values", "int[]", "void append(int... value);")));

        assertTrue(result.success(), result.diagnostics());
        String generated = result.source("sample/ValuesImpl.java");
        assertTrue(generated.contains("ObjectBuffer<int[]>"), generated);
        assertTrue(generated.contains("public void append(int[] value)"), generated);
    }

    @Test
    void nestedPublicInterfacesUseBinaryNamesAndDistinctImplementations() throws Exception {
        Compilation result = compile(Map.of("sample.Outer", """
                package sample;
                public class Outer {
                    @org.batcher.BatchInputType
                    public interface Input extends org.batcher.BatchInput<String> { void append(String value); }
                    public static class Inner {
                        @org.batcher.BatchInputType
                        public interface Input extends org.batcher.BatchInput<Integer> { void append(int value); }
                    }
                }
                """));

        assertTrue(result.success(), result.diagnostics());
        assertTrue(result.source("sample/Outer$InputImpl.java").contains("implements sample.Outer.Input"));
        assertTrue(result.source("sample/Outer$Inner$InputImpl.java").contains("implements sample.Outer.Inner.Input"));
        assertTrue(Files.exists(result.classes().resolve("sample/Outer$InputImpl.class")));
        assertTrue(Files.exists(result.classes().resolve("sample/Outer$Inner$InputImpl.class")));
    }

    @Test
    void supportsUnnamedPackage() throws Exception {
        Compilation result = compile(Map.of("Text", """
                @org.batcher.BatchInputType
                public interface Text extends org.batcher.BatchInput<String> { void append(String value); }
                """));

        assertTrue(result.success(), result.diagnostics());
        assertTrue(result.source("TextImpl.java").startsWith("public final class TextImpl"));
    }

    @Test
    void generatedImplementationsHavePublicConstructorsAndDelegateToRuntimeBuffers() throws Exception {
        Compilation result = compile(Map.of("sample.Usage", """
                package sample;
                public class Usage {
                    @org.batcher.BatchInputType
                    public interface Numbers extends org.batcher.BatchInput<Integer> { void append(int value); }
                    @org.batcher.BatchInputType
                    public interface Text extends org.batcher.BatchInput<String> { void append(String value); }
                    public static Object exercise() {
                        java.util.List<Integer> numbers = new java.util.concurrent.CopyOnWriteArrayList<>();
                        java.util.List<String> text = new java.util.concurrent.CopyOnWriteArrayList<>();
                        org.batcher.BatchSettings settings = new org.batcher.BatchSettings(10, java.time.Duration.ofHours(1));
                        try (Numbers input = new Usage$NumbersImpl(settings, numbers::addAll)) {
                            input.append(7);
                            input.append(9);
                        }
                        try (Text input = new Usage$TextImpl(settings, text::addAll)) {
                            input.append("one");
                            input.append("two");
                        }
                        return java.util.List.of(numbers, text);
                    }
                }
                """));

        assertTrue(result.success(), result.diagnostics());
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {result.classes().toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> usage = Class.forName("sample.Usage", true, loader);
            assertEquals(List.of(List.of(7, 9), List.of("one", "two")), usage.getMethod("exercise").invoke(null));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidInputs")
    void rejectsInvalidContracts(String name, String source, String expectedMessage) throws Exception {
        Compilation result = compile(Map.of("sample.Bad", source));

        assertFalse(result.success(), name);
        assertTrue(result.diagnostics().contains(expectedMessage), result.diagnostics());
        try (Stream<Path> generated = Files.walk(result.generated())) {
            assertFalse(generated.anyMatch(path -> path.toString().endsWith("Impl.java")), result.diagnostics());
        }
    }

    private static Stream<Arguments> invalidInputs() {
        return Stream.of(
                invalid("class", """
                        package sample;
                        @org.batcher.BatchInputType public class Bad { }
                        """, "only supported on interfaces"),
                invalid("nonpublic interface", """
                        package sample;
                        @org.batcher.BatchInputType interface Bad extends org.batcher.BatchInput<String> {
                            void append(String value);
                        }
                        """, "all enclosing types must be public"),
                invalid("nonpublic enclosing class", """
                        package sample;
                        class Bad {
                            @org.batcher.BatchInputType
                            public interface Input extends org.batcher.BatchInput<String> { void append(String value); }
                        }
                        """, "all enclosing types must be public"),
                invalid("nonpublic enclosing intermediate class", """
                        package sample;
                        public class Bad {
                            private static class Hidden {
                                @org.batcher.BatchInputType
                                public interface Input extends org.batcher.BatchInput<String> { void append(String value); }
                            }
                        }
                        """, "all enclosing types must be public"),
                invalid("generic interface", """
                        package sample;
                        @org.batcher.BatchInputType
                        public interface Bad<T> extends org.batcher.BatchInput<T> { void append(T value); }
                        """, "interfaces must not be generic"),
                invalid("missing BatchInput", """
                        package sample;
                        @org.batcher.BatchInputType public interface Bad { void append(String value); }
                        """, "must extend BatchInput<T>"),
                invalid("AutoCloseable alone", """
                        package sample;
                        @org.batcher.BatchInputType public interface Bad extends AutoCloseable { void append(String value); }
                        """, "must extend BatchInput<T>"),
                invalid("raw BatchInput", """
                        package sample;
                        @org.batcher.BatchInputType
                        public interface Bad extends org.batcher.BatchInput { void append(Object value); }
                        """, "fully resolved element type"),
                invalid("raw inherited parent", """
                        package sample;
                        interface Parent<T> extends org.batcher.BatchInput<T> { void append(T value); }
                        @org.batcher.BatchInputType public interface Bad extends Parent { }
                        """, "fully resolved element type"),
                invalid("raw element", input("Bad", "java.util.List", "void append(java.util.List value);"),
                        "raw types and type variables are not supported"),
                invalid("no input", input("Bad", "String", ""), "exactly one abstract input method"),
                invalid("default input only", input("Bad", "String", "default void append(String value) { }"),
                        "exactly one abstract input method"),
                invalid("additional abstract method", input("Bad", "String", "void append(String value); int size();"),
                        "found 2"),
                invalid("incorrect close overload", input("Bad", "String", "void append(String value); void close(int reason);"),
                        "found 2"),
                invalid("no argument", input("Bad", "String", "void append();"), "take exactly one argument"),
                invalid("two arguments", input("Bad", "String", "void append(String first, String second);"),
                        "take exactly one argument"),
                invalid("nonvoid result", input("Bad", "String", "int append(String value);"), "must return void"),
                invalid("generic method", input("Bad", "String", "<U> void append(String value);"), "method must not be generic"),
                invalid("checked throws", input("Bad", "String", "void append(String value) throws java.io.IOException;"),
                        "must not declare checked exceptions"),
                invalid("inherited checked throws", """
                        package sample;
                        interface Parent<T> { void append(T value) throws Exception; }
                        @org.batcher.BatchInputType
                        public interface Bad extends org.batcher.BatchInput<String>, Parent<String> { }
                        """, "must not declare checked exceptions"),
                invalid("wrong reference", input("Bad", "String", "void append(Object value);"), "must exactly match"),
                invalid("assignable but not equal", input("Bad", "Number", "void append(Integer value);"), "must exactly match"),
                invalid("wrong parameterization", input("Bad", "java.util.List<String>", "void append(java.util.List<Integer> value);"),
                        "must exactly match"),
                invalid("int for Number", input("Bad", "Number", "void append(int value);"), "int input requires BatchInput<Integer>"),
                invalid("long", input("Bad", "Long", "void append(long value);"), "Unsupported primitive input type long"),
                invalid("boolean", input("Bad", "Boolean", "void append(boolean value);"), "Unsupported primitive input type boolean"),
                invalid("double", input("Bad", "Double", "void append(double value);"), "Unsupported primitive input type double"),
                invalid("sealed", """
                        package sample;
                        @org.batcher.BatchInputType
                        public sealed interface Bad extends org.batcher.BatchInput<String> permits Child { void append(String value); }
                        non-sealed interface Child extends Bad { }
                        """, "does not support sealed interfaces"));
    }

    @Test
    void initRejectsAHandlerWithTheWrongElementTypeAtCompileTime() throws Exception {
        Compilation result = compile(Map.of("sample.Usage", """
                package sample;
                public class Usage {
                    @org.batcher.BatchInputType
                    public interface Numbers extends org.batcher.BatchInput<Integer> { void append(int value); }
                    public static void create() {
                        java.util.function.Consumer<java.util.List<String>> handler = batch -> { };
                        org.batcher.Batcher.init(Numbers.class, org.batcher.BatchSettings.defaults(), handler);
                    }
                }
                """));

        assertFalse(result.success(), "The input and handler must share the same element type");
        assertTrue(result.diagnostics().contains("init"), result.diagnostics());
    }

    @Test
    void reportsExistingImplementationNameInsteadOfOverwritingIt() throws Exception {
        Compilation result = compile(Map.of(
                "sample.Text", input("Text", "String", "void append(String value);"),
                "sample.TextImpl", "package sample; public class TextImpl { }"));

        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("Cannot generate sample.TextImpl: that type already exists"), result.diagnostics());
        assertTrue(result.diagnostics().contains("binary name + Impl"), result.diagnostics());
        assertFalse(Files.exists(result.generated().resolve("sample/TextImpl.java")));
    }

    private static Arguments invalid(String name, String source, String expectedMessage) {
        return Arguments.of(name, source, expectedMessage);
    }

    private static String input(String name, String itemType, String methods) {
        return "package sample;\n@org.batcher.BatchInputType\npublic interface " + name
                + " extends org.batcher.BatchInput<" + itemType + "> {\n" + methods + "\n}\n";
    }

    private Compilation compile(Map<String, String> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Processor tests require a JDK, not a JRE");
        Path classes = Files.createDirectories(temporaryDirectory.resolve("classes"));
        Path generated = Files.createDirectories(temporaryDirectory.resolve("generated"));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<String> options = List.of("-classpath",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    "-proc:full", "-d", classes.toString(), "-s", generated.toString());
            List<JavaFileObject> units = sources.entrySet().stream()
                    .map(entry -> (JavaFileObject) new Source(entry.getKey(), entry.getValue())).toList();
            JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostics, options, null, units);
            task.setProcessors(List.of(new BatchInputProcessor()));
            boolean success = task.call();
            String messages = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .map(diagnostic -> diagnostic.getMessage(java.util.Locale.ROOT))
                    .collect(Collectors.joining("\n"));
            return new Compilation(success, messages, classes, generated);
        }
    }

    private record Compilation(boolean success, String diagnostics, Path classes, Path generated) {
        String source(String relativePath) throws IOException {
            return Files.readString(generated.resolve(relativePath));
        }
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String content;

        Source(String className, String content) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
