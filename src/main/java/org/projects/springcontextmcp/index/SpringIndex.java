package org.projects.springcontextmcp.index;

import java.util.*;
import java.util.stream.Collectors;

/**
 * In-memory structural index of a Spring codebase.
 *
 * Everything here is derived statically. The point of the whole project is that
 * the runtime graph (which impl is behind an interface, what a request actually
 * touches) is invisible to text search but recoverable from the AST.
 */
public final class SpringIndex {

    public enum Stereotype { COMPONENT, SERVICE, REPOSITORY, CONTROLLER, REST_CONTROLLER, CONFIGURATION, BEAN_METHOD }

    public enum InjectionKind { CONSTRUCTOR, FIELD, SETTER }

    /** A Spring-managed bean. */
    public record BeanDef(
            String fqn,
            String simpleName,
            Stereotype stereotype,
            List<String> interfaces,     // fqns of implemented interfaces
            String beanName,             // explicit @Service("x") / @Bean name, else decapitalized simple name
            String qualifier,            // @Qualifier on the definition, may be null
            boolean primary,
            String file,
            int line
    ) {}

    /** A place where a dependency is injected. */
    public record InjectionPoint(
            String ownerFqn,
            String declaredType,         // fqn of the DECLARED type - usually an interface
            String name,
            InjectionKind kind,
            String qualifier,            // may be null
            int line
    ) {}

    /** An HTTP entry point. */
    public record Endpoint(
            String httpMethod,           // GET / POST / ... or ANY
            String path,                 // class-level @RequestMapping + method-level, normalized
            String controllerFqn,
            String methodName,
            String signature,            // e.g. "ResponseEntity<Owner> find(Long id)"
            String file,
            int line
    ) {}

    /** A resolved call from one type's method into another type's method. */
    public record CallEdge(
            String callerFqn,
            String callerMethod,
            String calleeFqn,
            String calleeMethod,
            int line
    ) {}

    /** A JPA entity relationship. */
    public record Relationship(
            String ownerFqn,
            String fieldName,
            String kind,                 // ONE_TO_MANY / MANY_TO_ONE / ONE_TO_ONE / MANY_TO_MANY
            String targetFqn,
            String fetch,                // LAZY / EAGER (explicit or JPA default)
            String mappedBy              // may be null
    ) {}

    private final List<BeanDef> beans;
    private final List<InjectionPoint> injections;
    private final List<Endpoint> endpoints;
    private final List<CallEdge> calls;
    private final List<Relationship> relationships;
    private final Map<String, List<String>> interfaceToImpls;   // interface fqn -> impl fqns
    private final Map<String, BeanDef> beansByFqn;

    public SpringIndex(List<BeanDef> beans,
                       List<InjectionPoint> injections,
                       List<Endpoint> endpoints,
                       List<CallEdge> calls,
                       List<Relationship> relationships) {
        this.beans = List.copyOf(beans);
        this.injections = List.copyOf(injections);
        this.endpoints = List.copyOf(endpoints);
        this.calls = List.copyOf(calls);
        this.relationships = List.copyOf(relationships);
        this.beansByFqn = beans.stream()
                .collect(Collectors.toMap(BeanDef::fqn, b -> b, (a, b) -> a));

        Map<String, List<String>> idx = new HashMap<>();
        for (BeanDef b : beans) {
            for (String iface : b.interfaces()) {
                idx.computeIfAbsent(iface, k -> new ArrayList<>()).add(b.fqn());
            }
        }
        this.interfaceToImpls = idx;
    }

    public List<BeanDef> beans() { return beans; }
    public List<Endpoint> endpoints() { return endpoints; }
    public List<Relationship> relationships() { return relationships; }
    public Optional<BeanDef> bean(String fqn) { return Optional.ofNullable(beansByFqn.get(fqn)); }

    /** Candidate implementations of a type, ordered the way Spring would resolve them. */
    public List<BeanDef> implementationsOf(String typeFqn, String qualifierAtInjectionPoint) {
        List<String> candidates = new ArrayList<>(interfaceToImpls.getOrDefault(typeFqn, List.of()));
        if (beansByFqn.containsKey(typeFqn)) candidates.add(typeFqn);   // concrete type injected directly

        List<BeanDef> defs = candidates.stream()
                .distinct()
                .map(beansByFqn::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));

        // Mirror Spring's own resolution order so the agent sees what the container sees.
        defs.sort(Comparator
                .comparing((BeanDef b) -> matchesQualifier(b, qualifierAtInjectionPoint) ? 0 : 1)
                .thenComparing(b -> b.primary() ? 0 : 1)
                .thenComparing(BeanDef::fqn));
        return defs;
    }

    private static boolean matchesQualifier(BeanDef b, String q) {
        if (q == null) return false;
        return q.equals(b.qualifier()) || q.equals(b.beanName());
    }

    public List<InjectionPoint> injectionsIn(String ownerFqn) {
        return injections.stream().filter(i -> i.ownerFqn().equals(ownerFqn)).toList();
    }

    public List<InjectionPoint> injectionsOf(String declaredTypeFqn) {
        return injections.stream().filter(i -> i.declaredType().equals(declaredTypeFqn)).toList();
    }

    public List<CallEdge> callsFrom(String fqn, String method) {
        return calls.stream()
                .filter(c -> c.callerFqn().equals(fqn) && (method == null || c.callerMethod().equals(method)))
                .toList();
    }

    public List<CallEdge> callsTo(String fqn, String method) {
        return calls.stream()
                .filter(c -> c.calleeFqn().equals(fqn) && (method == null || c.calleeMethod().equals(method)))
                .toList();
    }

    /** Resolve a fuzzy name ("OwnerService", "owner service") to concrete fqns. */
    public List<String> resolveType(String query) {
        String norm = query.toLowerCase().replaceAll("[^a-z0-9]", "");
        List<String> exact = beans.stream()
                .filter(b -> b.fqn().equals(query) || b.simpleName().equals(query))
                .map(BeanDef::fqn).toList();
        if (!exact.isEmpty()) return exact;
        return beans.stream()
                .filter(b -> b.simpleName().toLowerCase().replaceAll("[^a-z0-9]", "").contains(norm))
                .map(BeanDef::fqn)
                .limit(10)
                .toList();
    }

    public Stats stats() {
        return new Stats(beans.size(), endpoints.size(), injections.size(), calls.size(), relationships.size());
    }

    public record Stats(int beans, int endpoints, int injections, int callEdges, int relationships) {}
}

