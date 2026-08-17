package org.projects.springcontextmcp.index;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.SourceRoot;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.projects.springcontextmcp.index.SpringIndex.*;

/**
 * Walks a Spring project's sources and produces a {@link SpringIndex}.
 *
 * Symbol resolution is best-effort: a missing dependency jar makes {@code resolve()}
 * throw, and that must degrade to a partial edge rather than kill the scan. A tool
 * that fails closed on one unresolvable call is useless on real codebases.
 */
public final class IndexBuilder {

    private static final Set<String> STEREOTYPE_ANNOTATIONS = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration");

    private static final Map<String, String> MAPPING_ANNOTATIONS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH",
            "RequestMapping", "ANY");

    private static final Map<String, String> RELATIONSHIP_ANNOTATIONS = Map.of(
            "OneToMany", "ONE_TO_MANY",
            "ManyToOne", "MANY_TO_ONE",
            "OneToOne", "ONE_TO_ONE",
            "ManyToMany", "MANY_TO_MANY");

    // JPA defaults - the source rarely states these, and getting them wrong is
    // exactly the N+1 mistake an agent makes when it only reads the text.
    private static final Map<String, String> DEFAULT_FETCH = Map.of(
            "ONE_TO_MANY", "LAZY",
            "MANY_TO_MANY", "LAZY",
            "MANY_TO_ONE", "EAGER",
            "ONE_TO_ONE", "EAGER");

    private final Path projectRoot;

    private final List<BeanDef> beans = new ArrayList<>();
    private final List<InjectionPoint> injections = new ArrayList<>();
    private final List<Endpoint> endpoints = new ArrayList<>();
    private final List<CallEdge> calls = new ArrayList<>();
    private final List<Relationship> relationships = new ArrayList<>();
    private final List<PreHandler> preHandlers = new ArrayList<>();
    private final List<MethodLoc> methodLocs = new ArrayList<>();

    public IndexBuilder(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public SpringIndex build() throws Exception {
        List<Path> sourceRoots = findSourceRoots(projectRoot);
        if (sourceRoots.isEmpty()) throw new IllegalStateException("No src/main/java found under " + projectRoot);

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver(false));
        sourceRoots.forEach(r -> typeSolver.add(new JavaParserTypeSolver(r)));

        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

        for (Path root : sourceRoots) {
            SourceRoot sr = new SourceRoot(root, config);
            sr.tryToParse().stream()
                    .filter(r -> r.getResult().isPresent())
                    .map(r -> r.getResult().get())
                    .forEach(this::visit);
        }

        return new SpringIndex(beans, injections, endpoints, calls, relationships, preHandlers, methodLocs);
    }

    private static List<Path> findSourceRoots(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root, 6)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> p.endsWith(Path.of("src", "main", "java")))
                    .toList();
        }
    }

    private void visit(CompilationUnit cu) {
        String file = cu.getStorage().map(st -> st.getPath().toString()).orElse("<unknown>");

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqn = cls.getFullyQualifiedName().orElse(cls.getNameAsString());
            int line = cls.getBegin().map(p -> p.line).orElse(0);

            Optional<Stereotype> stereotype = stereotypeOf(cls);
            boolean isEntity = cls.getAnnotationByName("Entity").isPresent();

            if (stereotype.isPresent()) {
                beans.add(new BeanDef(
                        fqn,
                        cls.getNameAsString(),
                        stereotype.get(),
                        implementedTypes(cls),
                        beanNameOf(cls),
                        annotationValue(cls, "Qualifier").orElse(null),
                        cls.getAnnotationByName("Primary").isPresent(),
                        file,
                        line));

                collectInjections(cls, fqn);
                collectCalls(cls, fqn);
                collectMethodLocs(cls, fqn, file);

                if (stereotype.get() == Stereotype.CONTROLLER || stereotype.get() == Stereotype.REST_CONTROLLER) {
                    collectEndpoints(cls, fqn, file);
                    collectPreHandlers(cls, fqn);
                }
            }

            if (isEntity) collectRelationships(cls, fqn);
        }
    }

    // ---------- beans ----------

    private Optional<Stereotype> stereotypeOf(ClassOrInterfaceDeclaration cls) {
        for (String a : STEREOTYPE_ANNOTATIONS) {
            if (cls.getAnnotationByName(a).isPresent()) {
                return Optional.of(switch (a) {
                    case "Service" -> Stereotype.SERVICE;
                    case "Repository" -> Stereotype.REPOSITORY;
                    case "Controller" -> Stereotype.CONTROLLER;
                    case "RestController" -> Stereotype.REST_CONTROLLER;
                    case "Configuration" -> Stereotype.CONFIGURATION;
                    default -> Stereotype.COMPONENT;
                });
            }
        }
        // Spring Data repositories are interfaces with no annotation at all -
        // pure text search never finds these.
        if (cls.isInterface() && cls.getExtendedTypes().stream()
                .anyMatch(t -> t.getNameAsString().endsWith("Repository"))) {
            return Optional.of(Stereotype.REPOSITORY);
        }
        return Optional.empty();
    }

    private String beanNameOf(ClassOrInterfaceDeclaration cls) {
        for (String a : STEREOTYPE_ANNOTATIONS) {
            Optional<String> explicit = annotationValue(cls, a);
            if (explicit.isPresent() && !explicit.get().isBlank()) return explicit.get();
        }
        String n = cls.getNameAsString();
        return Character.toLowerCase(n.charAt(0)) + n.substring(1);
    }

    private List<String> implementedTypes(ClassOrInterfaceDeclaration cls) {
        List<String> out = new ArrayList<>();
        Stream.concat(cls.getImplementedTypes().stream(), cls.getExtendedTypes().stream())
                .forEach(t -> out.add(resolveTypeName(t)));
        return out;
    }

    // ---------- injection ----------

    private void collectInjections(ClassOrInterfaceDeclaration cls, String ownerFqn) {
        // Constructor injection - single constructor needs no @Autowired since 4.3
        List<ConstructorDeclaration> ctors = cls.getConstructors();
        ConstructorDeclaration target = ctors.stream()
                .filter(c -> c.getAnnotationByName("Autowired").isPresent())
                .findFirst()
                .orElse(ctors.size() == 1 ? ctors.get(0) : null);

        if (target != null) {
            for (Parameter p : target.getParameters()) {
                injections.add(new InjectionPoint(
                        ownerFqn,
                        resolveTypeName(p.getType()),
                        p.getNameAsString(),
                        InjectionKind.CONSTRUCTOR,
                        annotationValue(p, "Qualifier").orElse(null),
                        p.getBegin().map(x -> x.line).orElse(0)));
            }
        }

        for (FieldDeclaration f : cls.getFields()) {
            if (f.getAnnotationByName("Autowired").isEmpty() && f.getAnnotationByName("Inject").isEmpty()) continue;
            for (VariableDeclarator v : f.getVariables()) {
                injections.add(new InjectionPoint(
                        ownerFqn,
                        resolveTypeName(v.getType()),
                        v.getNameAsString(),
                        InjectionKind.FIELD,
                        annotationValue(f, "Qualifier").orElse(null),
                        f.getBegin().map(x -> x.line).orElse(0)));
            }
        }
    }

    // ---------- endpoints ----------

    private void collectEndpoints(ClassOrInterfaceDeclaration cls, String fqn, String file) {
        String base = annotationValue(cls, "RequestMapping").orElse("");

        for (MethodDeclaration m : cls.getMethods()) {
            for (Map.Entry<String, String> e : MAPPING_ANNOTATIONS.entrySet()) {
                Optional<AnnotationExpr> ann = m.getAnnotationByName(e.getKey());
                if (ann.isEmpty()) continue;

                String sub = annotationValue(m, e.getKey()).orElse("");
                endpoints.add(new Endpoint(
                        e.getValue(),
                        normalizePath(base, sub),
                        fqn,
                        m.getNameAsString(),
                        signatureOf(m),
                        file,
                        m.getBegin().map(p -> p.line).orElse(0)));
            }
        }
    }

    private static String normalizePath(String base, String sub) {
        String joined = ("/" + base + "/" + sub).replaceAll("/{2,}", "/");
        if (joined.length() > 1 && joined.endsWith("/")) joined = joined.substring(0, joined.length() - 1);
        return joined;
    }

    /** Signature only - never the body. Bodies are what blow the context window. */
    private static String signatureOf(MethodDeclaration m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getType().asString()).append(' ').append(m.getNameAsString()).append('(');
        for (int i = 0; i < m.getParameters().size(); i++) {
            if (i > 0) sb.append(", ");
            Parameter p = m.getParameter(i);
            sb.append(p.getType().asString()).append(' ').append(p.getNameAsString());
        }
        return sb.append(')').toString();
    }

    // ---------- call graph ----------

    private void collectCalls(ClassOrInterfaceDeclaration cls, String callerFqn) {
        // Local symbol table: field and parameter names -> declared type.
        // Built first because name lookup beats the symbol solver here - the target
        // project's dependency jars are not on the type solver's path, so resolve()
        // throws on every call into Spring Data, JPA, or any other library. The
        // declared type of the receiver is what we want anyway.
        Map<String, String> fields = new HashMap<>();
        for (FieldDeclaration f : cls.getFields()) {
            for (VariableDeclarator v : f.getVariables()) {
                fields.put(v.getNameAsString(), resolveTypeName(v.getType()));
            }
        }
        for (ConstructorDeclaration ctor : cls.getConstructors()) {
            for (Parameter p : ctor.getParameters()) {
                fields.putIfAbsent(p.getNameAsString(), resolveTypeName(p.getType()));
            }
        }

        for (MethodDeclaration m : cls.getMethods()) {
            String callerMethod = m.getNameAsString();

            Map<String, String> scope = new HashMap<>(fields);
            for (Parameter p : m.getParameters()) {
                scope.put(p.getNameAsString(), resolveTypeName(p.getType()));
            }

            for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                String receiver = receiverType(call, scope, callerFqn);
                if (receiver == null || receiver.startsWith("java.")) continue;
                calls.add(new CallEdge(
                        callerFqn, callerMethod,
                        receiver, call.getNameAsString(),
                        call.getBegin().map(p -> p.line).orElse(0)));
            }
        }
    }

    /**
     * The type of the object a call is made ON, not the type that declares the method.
     * repository.findAll() must record VetRepository, not PagingAndSortingRepository -
     * the super-interface tells the agent nothing about what the route touches.
     */
    private String receiverType(MethodCallExpr call, Map<String, String> scope, String callerFqn) {
        Optional<com.github.javaparser.ast.expr.Expression> maybeScope = call.getScope();
        if (maybeScope.isEmpty()) return callerFqn;   // unqualified call = self-invocation

        com.github.javaparser.ast.expr.Expression expr = maybeScope.get();

        String name = receiverName(expr);
        if (name != null) {
            String declared = scope.get(name);
            if (declared != null) return declared;
        }

        try {
            return expr.calculateResolvedType().describe();
        } catch (Exception | StackOverflowError ignored) {
            return null;
        }
    }

    /** Handles both `repository.x()` and `this.repository.x()`. */
    private static String receiverName(com.github.javaparser.ast.expr.Expression expr) {
        if (expr instanceof NameExpr n) return n.getNameAsString();
        if (expr instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) return f.getNameAsString();
        return null;
    }

    // ---------- JPA ----------

    private void collectRelationships(ClassOrInterfaceDeclaration cls, String ownerFqn) {
        for (FieldDeclaration f : cls.getFields()) {
            for (Map.Entry<String, String> e : RELATIONSHIP_ANNOTATIONS.entrySet()) {
                Optional<AnnotationExpr> ann = f.getAnnotationByName(e.getKey());
                if (ann.isEmpty()) continue;

                String kind = e.getValue();
                String fetch = annotationMember(ann.get(), "fetch")
                        .map(v -> v.substring(v.lastIndexOf('.') + 1))
                        .orElse(DEFAULT_FETCH.get(kind));
                String mappedBy = annotationMember(ann.get(), "mappedBy").orElse(null);

                for (VariableDeclarator v : f.getVariables()) {
                    relationships.add(new Relationship(
                            ownerFqn,
                            v.getNameAsString(),
                            kind,
                            unwrapCollection(v.getType().asString()),
                            fetch,
                            mappedBy));
                }
            }
        }
    }

    private static String unwrapCollection(String type) {
        int lt = type.indexOf('<');
        int gt = type.lastIndexOf('>');
        return (lt >= 0 && gt > lt) ? type.substring(lt + 1, gt).trim() : type;
    }

    // ---------- helpers ----------

    private String resolveTypeName(com.github.javaparser.ast.type.Type t) {
        try {
            return t.resolve().describe();
        } catch (Exception | StackOverflowError ignored) {
            return t instanceof ClassOrInterfaceType c ? c.getNameAsString() : t.asString();
        }
    }

    /** Reads the single-value member of an annotation, e.g. @Service("foo") or @GetMapping(path="/x"). */
    private static Optional<String> annotationValue(NodeWithAnnotations<?> node, String annotationName) {
        return node.getAnnotationByName(annotationName).flatMap(ann -> {
            if (ann instanceof SingleMemberAnnotationExpr s) return Optional.of(literal(s.getMemberValue()));
            if (ann instanceof NormalAnnotationExpr n) {
                for (String key : List.of("value", "path", "name")) {
                    Optional<String> v = annotationMember(n, key);
                    if (v.isPresent()) return v;
                }
            }
            return Optional.empty();
        });
    }

    private static Optional<String> annotationMember(AnnotationExpr ann, String key) {
        if (ann instanceof NormalAnnotationExpr n) {
            return n.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals(key))
                    .findFirst()
                    .map(p -> literal(p.getValue()));
        }
        return Optional.empty();
    }

    private static String literal(Expression e) {
        if (e instanceof StringLiteralExpr s) return s.getValue();
        if (e instanceof ArrayInitializerExpr a && !a.getValues().isEmpty()) return literal(a.getValues().get(0));
        return e.toString().replace("\"", "");
    }

    private void collectPreHandlers(ClassOrInterfaceDeclaration cls, String fqn) {
        for (MethodDeclaration m : cls.getMethods()) {
            String kind = m.getAnnotationByName("ModelAttribute").isPresent() ? "MODEL_ATTRIBUTE"
                    : m.getAnnotationByName("InitBinder").isPresent() ? "INIT_BINDER"
                    : null;
            if (kind == null) continue;

            // Spring runs these before every handler in the controller. A handler that
            // looks like it touches nothing may already have hit the database here.
            preHandlers.add(new PreHandler(fqn, m.getNameAsString(), kind,
                    signatureOf(m), m.getBegin().map(p -> p.line).orElse(0)));
        }
    }

    private void collectMethodLocs(ClassOrInterfaceDeclaration cls, String fqn, String file) {
        for (MethodDeclaration m : cls.getMethods()) {
            methodLocs.add(new MethodLoc(fqn, m.getNameAsString(), signatureOf(m), file,
                    m.getBegin().map(p -> p.line).orElse(0),
                    m.getEnd().map(p -> p.line).orElse(0)));
        }
    }
}

