package org.projects.springcontextmcp.tools;

import org.projects.springcontextmcp.index.SpringIndex;
import org.projects.springcontextmcp.index.SpringIndex.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * Tool implementations.
 *
 * Design rule that governs every method here: a tool result goes straight into the
 * model's context window. A tool that returns a full AST dump makes the agent WORSE
 * than grep, because it burns the budget that the agent needs for reasoning. So every
 * result is (a) signatures not bodies, (b) capped, (c) accompanied by an explicit
 * handle the agent can use to drill in.
 */
public final class SpringTools {

    /** ~4 chars per token is close enough for budgeting; exactness is not the point. */
    private static final int CHARS_PER_TOKEN = 4;
    private static final int DEFAULT_BUDGET_TOKENS = 800;
    private static final int MAX_CHAIN_DEPTH = 6;

    private final SpringIndex index;
    private final List<ToolCallRecord> trace = new ArrayList<>();

    public SpringTools(SpringIndex index) {
        this.index = index;
    }

    /** Every call is recorded - this is the raw material for the benchmark. */
    public record ToolCallRecord(String tool, Map<String, Object> args, int resultTokens, long millis) {}

    public List<ToolCallRecord> trace() { return List.copyOf(trace); }

    // =========================================================================
    // find_implementations
    // =========================================================================

    /**
     * Given an interface (or fuzzy name), return the concrete beans behind it in
     * Spring's own resolution order. This is the single highest-value tool: an agent
     * reading `private final OwnerService service;` has no textual path to the impl.
     */
    public String findImplementations(String type, String qualifier) {
        return timed("find_implementations", Map.of("type", type, "qualifier", String.valueOf(qualifier)), () -> {
            List<String> targets = index.resolveType(type);
            if (targets.isEmpty()) {
                return err("No bean or interface matching '" + type + "'.",
                        "Try list_beans with a stereotype filter, or pass a fully-qualified name.");
            }

            StringBuilder sb = new StringBuilder();
            for (String t : targets.subList(0, Math.min(3, targets.size()))) {
                List<BeanDef> impls = index.implementationsOf(t, qualifier);
                sb.append(t).append('\n');
                if (impls.isEmpty()) {
                    sb.append("  (no Spring-managed implementation found)\n");
                    continue;
                }
                for (int i = 0; i < impls.size(); i++) {
                    BeanDef b = impls.get(i);
                    sb.append("  ").append(i == 0 ? "-> " : "   ").append(b.fqn())
                            .append("  [").append(b.stereotype()).append(", bean='").append(b.beanName()).append("'");
                    if (b.primary()) sb.append(", @Primary");
                    if (b.qualifier() != null) sb.append(", @Qualifier(\"").append(b.qualifier()).append("\")");
                    sb.append("]  ").append(b.file()).append(':').append(b.line()).append('\n');
                }
                if (impls.size() > 1) {
                    sb.append("  NOTE: ").append(impls.size())
                            .append(" candidates. Arrow marks what Spring injects here")
                            .append(qualifier != null ? " given @Qualifier(\"" + qualifier + "\")." : ".")
                            .append('\n');
                }
            }
            return sb.toString();
        });
    }

    // =========================================================================
    // trace_endpoint
    // =========================================================================

    /**
     * Full controller -> service -> repository chain for an HTTP route.
     *
     * Resolves through interfaces at every hop, which is the part a grep-based agent
     * cannot do: it finds the controller, then stalls because the next hop is an
     * interface reference with no implementation in the same file.
     */
    public String traceEndpoint(String path, String httpMethod, Integer budgetTokens) {
        int budget = budgetTokens == null ? DEFAULT_BUDGET_TOKENS : budgetTokens;
        return timed("trace_endpoint", Map.of("path", path, "method", String.valueOf(httpMethod)), () -> {

            // Exact match wins. Substring matching alone makes "/owners/{ownerId}"
            // silently return three unrelated /pets subroutes and not the one asked for.
            List<Endpoint> exact = index.endpoints().stream()
                    .filter(e -> e.path().equalsIgnoreCase(path))
                    .filter(e -> httpMethod == null || e.httpMethod().equalsIgnoreCase(httpMethod)
                            || e.httpMethod().equals("ANY"))
                    .toList();

            List<Endpoint> matches = exact.isEmpty()
                    ? index.endpoints().stream()
                    .filter(e -> e.path().toLowerCase().contains(path.toLowerCase()))
                    .filter(e -> httpMethod == null || e.httpMethod().equalsIgnoreCase(httpMethod)
                            || e.httpMethod().equals("ANY"))
                    .toList()
                    : exact;

            if (matches.isEmpty()) {
                List<String> near = index.endpoints().stream()
                        .map(Endpoint::path).distinct().limit(15).toList();
                return err("No endpoint matching '" + path + "'.", "Known paths include: " + near);
            }

            StringBuilder sb = new StringBuilder();
            for (Endpoint e : matches.subList(0, Math.min(3, matches.size()))) {
                sb.append(e.httpMethod()).append(' ').append(e.path()).append('\n');
                sb.append("  ").append(shortName(e.controllerFqn())).append('.').append(e.signature())
                        .append("   ").append(e.file()).append(':').append(e.line()).append('\n');
                walk(sb, e.controllerFqn(), e.methodName(), 1, new HashSet<>(), budget);
                sb.append('\n');
            }
            sb.append("Use expand(fqn, method) for a body. Signatures only above, by design.\n");
            return truncate(sb.toString(), budget);
        });
    }

    private void walk(StringBuilder sb, String fqn, String method, int depth, Set<String> seen, int budget) {
        if (depth > MAX_CHAIN_DEPTH) { sb.append(indent(depth)).append("... (depth limit)\n"); return; }
        if (estTokens(sb.toString()) > budget) return;

        String key = fqn + "#" + method;
        if (!seen.add(key)) { sb.append(indent(depth)).append("(cycle -> ").append(shortName(fqn)).append(")\n"); return; }

        // Everything reachable inside this class first. Handlers routinely delegate to
        // a private helper that does the real work - VetController.showVetList calls
        // findPaginated, which calls the repository. Looking only at the entry method's
        // own calls reports "this route touches nothing", which is worse than useless.
        Set<String> local = localReachable(fqn, method);

        for (InjectionPoint ip : index.injectionsIn(fqn)) {
            List<BeanDef> impls = index.implementationsOf(ip.declaredType(), ip.qualifier());
            if (impls.isEmpty()) continue;
            BeanDef target = impls.get(0);

            // Which methods on the collaborator are hit, from anywhere in the local chain.
            Map<String, String> calledVia = new LinkedHashMap<>();   // callee method -> local method that called it
            for (String localMethod : local) {
                for (CallEdge c : index.callsFrom(fqn, localMethod)) {
                    if (c.calleeFqn().equals(target.fqn()) || c.calleeFqn().equals(ip.declaredType())) {
                        calledVia.putIfAbsent(c.calleeMethod(), localMethod);
                    }
                }
            }
            if (calledVia.isEmpty()) continue;

            sb.append(indent(depth)).append("-> ").append(shortName(target.fqn()))
                    .append(" [").append(target.stereotype()).append("]");
            if (!target.fqn().equals(ip.declaredType())) {
                sb.append("  (injected as ").append(shortName(ip.declaredType())).append(')');
            }
            sb.append('\n');

            calledVia.entrySet().stream().limit(6).forEach(e -> {
                sb.append(indent(depth + 1)).append('.').append(e.getKey()).append("()");
                // Surface the delegation path - an agent editing the handler needs to
                // know the call is actually made two frames down.
                if (!e.getValue().equals(method)) sb.append("   via ").append(e.getValue()).append("()");
                sb.append('\n');
            });

            calledVia.keySet().stream().limit(2)
                    .forEach(m -> walk(sb, target.fqn(), m, depth + 1, seen, budget));
        }
    }

    /** Methods reachable from an entry method through intra-class calls, including itself. */
    private Set<String> localReachable(String fqn, String entry) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(entry));
        while (!queue.isEmpty()) {
            String m = queue.poll();
            if (!visited.add(m)) continue;
            if (visited.size() > 40) break;   // pathological classes
            index.callsFrom(fqn, m).stream()
                    .filter(c -> c.calleeFqn().equals(fqn))
                    .map(CallEdge::calleeMethod)
                    .forEach(queue::add);
        }
        return visited;
    }

    // =========================================================================
    // entity_relationships
    // =========================================================================

    /**
     * JPA relationship graph with EFFECTIVE fetch types, including the ones the
     * source never states. @ManyToOne defaults to EAGER; an agent reading the file
     * sees no fetch attribute and assumes lazy. That assumption is how N+1 ships.
     */
    public String entityRelationships(String entity) {
        return timed("entity_relationships", Map.of("entity", entity), () -> {
            List<Relationship> rels = index.relationships().stream()
                    .filter(r -> r.ownerFqn().endsWith("." + entity) || r.ownerFqn().equals(entity)
                            || r.targetFqn().equals(entity))
                    .toList();
            if (rels.isEmpty()) return err("No JPA relationships found for '" + entity + "'.", null);

            StringBuilder sb = new StringBuilder();
            for (Relationship r : rels) {
                sb.append(shortName(r.ownerFqn())).append('.').append(r.fieldName())
                        .append("  ").append(r.kind())
                        .append(" -> ").append(r.targetFqn())
                        .append("  fetch=").append(r.fetch());
                if (r.mappedBy() != null) sb.append(" mappedBy=").append(r.mappedBy());
                sb.append('\n');
            }
            long eagerCollections = rels.stream()
                    .filter(r -> r.fetch().equals("EAGER") && !r.kind().equals("ONE_TO_ONE"))
                    .count();
            if (eagerCollections > 0) {
                sb.append("\nWARNING: ").append(eagerCollections)
                        .append(" EAGER association(s) - loading this entity in a list query is an N+1 risk.\n");
            }
            return sb.toString();
        });
    }

    // =========================================================================
    // find_callers / list_beans / expand
    // =========================================================================

    public String findCallers(String type, String method) {
        return timed("find_callers", Map.of("type", type, "method", String.valueOf(method)), () -> {
            List<String> targets = index.resolveType(type);
            if (targets.isEmpty()) return err("Unknown type '" + type + "'.", null);

            StringBuilder sb = new StringBuilder();
            for (String t : targets.subList(0, Math.min(2, targets.size()))) {
                // Callers of the interface count as callers of the impl.
                Set<String> aliases = new HashSet<>(List.of(t));
                index.bean(t).ifPresent(b -> aliases.addAll(b.interfaces()));

                List<CallEdge> in = aliases.stream()
                        .flatMap(a -> index.callsTo(a, method).stream())
                        .distinct().limit(40).toList();

                sb.append(shortName(t)).append(method != null ? "." + method + "()" : "")
                        .append("  <- ").append(in.size()).append(" caller(s)\n");
                in.forEach(c -> sb.append("  ").append(shortName(c.callerFqn())).append('.')
                        .append(c.callerMethod()).append("()  :").append(c.line()).append('\n'));
            }
            return sb.toString();
        });
    }

    public String listBeans(String stereotype, String packagePrefix) {
        return timed("list_beans", Map.of("stereotype", String.valueOf(stereotype)), () -> {
            List<BeanDef> filtered = index.beans().stream()
                    .filter(b -> stereotype == null || b.stereotype().name().equalsIgnoreCase(stereotype))
                    .filter(b -> packagePrefix == null || b.fqn().startsWith(packagePrefix))
                    .limit(100).toList();
            StringBuilder sb = new StringBuilder(filtered.size() + " bean(s)\n");
            filtered.forEach(b -> sb.append("  ").append(b.stereotype()).append("  ").append(b.fqn())
                    .append(b.primary() ? "  @Primary" : "").append('\n'));
            return sb.toString();
        });
    }

    public String stats() {
        SpringIndex.Stats s = index.stats();
        return "beans=%d endpoints=%d injections=%d callEdges=%d relationships=%d"
                .formatted(s.beans(), s.endpoints(), s.injections(), s.callEdges(), s.relationships());
    }

    // =========================================================================
    // plumbing
    // =========================================================================

    private String timed(String tool, Map<String, Object> args, Supplier<String> body) {
        long t0 = System.nanoTime();
        String out;
        try {
            out = body.get();
        } catch (Exception ex) {
            out = err("Tool failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    "The index may be partial. Fall back to reading files directly.");
        }
        trace.add(new ToolCallRecord(tool, args, estTokens(out), (System.nanoTime() - t0) / 1_000_000));
        return out;
    }

    /** Errors are written FOR the model: what went wrong plus what to do instead. */
    private static String err(String what, String recovery) {
        return "ERROR: " + what + (recovery == null ? "" : "\nNEXT: " + recovery);
    }

    private static int estTokens(String s) { return s.length() / CHARS_PER_TOKEN; }

    private static String truncate(String s, int budgetTokens) {
        int max = budgetTokens * CHARS_PER_TOKEN;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n... truncated at " + budgetTokens
                + " tokens. Narrow the query or raise budget_tokens.\n";
    }

    private static String indent(int d) { return "  ".repeat(d + 1); }

    private static String shortName(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }
}

