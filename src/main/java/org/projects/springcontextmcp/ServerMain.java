package org.projects.springcontextmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.projects.springcontextmcp.index.IndexBuilder;
import org.projects.springcontextmcp.index.SpringIndex;
import org.projects.springcontextmcp.tools.SpringTools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * MCP server over stdio.
 *
 * The transport is newline-delimited JSON-RPC 2.0. Implemented directly rather than
 * via the SDK: the surface we need is four methods, and owning it means no coupling
 * to an SDK whose API is still moving.
 *
 * INVARIANT: stdout is the transport. Anything written there that is not a JSON-RPC
 * message corrupts the stream and the client drops the connection with no useful
 * error. All logging goes to stderr. A stray System.out.println is the single most
 * common way to break an MCP server.
 *
 *   java -jar spring-context-mcp.jar /abs/path/to/spring-project
 */
public final class ServerMain {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "spring-context-mcp";
    private static final String SERVER_VERSION = "0.1.0";

    private static final ObjectMapper M = new ObjectMapper();

    /** JSON-RPC error codes. */
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final Path projectRoot;
    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    /**
     * Indexing a large repo takes seconds. MCP clients time out waiting for the
     * initialize response, so the scan runs on a background thread and the first
     * tool call blocks on it instead. Initialize stays fast; the cost lands where
     * the caller is already expecting latency.
     */
    private final CompletableFuture<SpringTools> toolsFuture;

    private record ToolDef(String name, String description, ObjectNode schema,
                           Function<JsonNode, String> handler) {}

    public ServerMain(Path projectRoot) {
        this.projectRoot = projectRoot;
        this.toolsFuture = CompletableFuture.supplyAsync(this::buildIndex);
        registerTools();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].isBlank()) {
            log("usage: java -jar spring-context-mcp.jar /path/to/spring-project");
            System.exit(2);
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (!root.toFile().isDirectory()) {
            log("not a directory: " + root);
            System.exit(2);
        }
        new ServerMain(root).serve();
    }

    private SpringTools buildIndex() {
        try {
            long t0 = System.currentTimeMillis();
            SpringIndex index = new IndexBuilder(projectRoot).build();
            SpringTools st = new SpringTools(index);
            log("indexed " + projectRoot + " in " + (System.currentTimeMillis() - t0) + "ms: " + st.stats());
            return st;
        } catch (Exception e) {
            log("INDEX FAILED: " + e);
            throw new CompletionException(e);
        }
    }

    // =========================================================================
    // transport
    // =========================================================================

    private void serve() throws IOException {
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;

            JsonNode request;
            try {
                request = M.readTree(line);
            } catch (Exception e) {
                write(out, error(null, PARSE_ERROR, "Invalid JSON: " + e.getMessage()));
                continue;
            }

            ObjectNode response;
            try {
                response = handle(request);
            } catch (Exception e) {
                log("handler threw: " + e);
                response = error(request.get("id"), INTERNAL_ERROR, String.valueOf(e.getMessage()));
            }

            // Notifications carry no id and must never be answered. Replying to one
            // is a protocol violation some clients treat as fatal.
            if (response != null) write(out, response);
        }
        log("stdin closed, exiting");
    }

    private static void write(BufferedWriter out, ObjectNode msg) throws IOException {
        out.write(M.writeValueAsString(msg));
        out.write('\n');
        out.flush();
    }

    private ObjectNode handle(JsonNode req) {
        String method = req.path("method").asText("");
        JsonNode id = req.get("id");
        boolean isNotification = (id == null || id.isNull());

        return switch (method) {
            case "initialize" -> result(id, initialize(req.path("params")));
            case "notifications/initialized", "notifications/cancelled" -> null;
            case "ping" -> result(id, M.createObjectNode());
            case "tools/list" -> result(id, listTools());
            case "tools/call" -> result(id, callTool(req.path("params")));
            default -> isNotification ? null : error(id, METHOD_NOT_FOUND, "Unknown method: " + method);
        };
    }

    // =========================================================================
    // protocol methods
    // =========================================================================

    private ObjectNode initialize(JsonNode params) {
        // Echo the client's protocol version when we can speak it; otherwise state ours.
        String requested = params.path("protocolVersion").asText(PROTOCOL_VERSION);

        ObjectNode caps = M.createObjectNode();
        caps.putObject("tools").put("listChanged", false);

        ObjectNode info = M.createObjectNode();
        info.put("name", SERVER_NAME);
        info.put("version", SERVER_VERSION);

        ObjectNode res = M.createObjectNode();
        res.put("protocolVersion", requested.isBlank() ? PROTOCOL_VERSION : requested);
        res.set("capabilities", caps);
        res.set("serverInfo", info);
        res.put("instructions",
                "Structural queries over a Spring codebase. Prefer these over grep when you need to know "
                        + "which class implements an interface, what an HTTP route actually touches, or whether a "
                        + "JPA association is lazy. Results are signatures, not bodies, and are token-capped.");
        return res;
    }

    private ObjectNode listTools() {
        ArrayNode arr = M.createArrayNode();
        for (ToolDef t : tools.values()) {
            ObjectNode n = M.createObjectNode();
            n.put("name", t.name());
            n.put("description", t.description());
            n.set("inputSchema", t.schema());
            arr.add(n);
        }
        ObjectNode res = M.createObjectNode();
        res.set("tools", arr);
        return res;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        ToolDef tool = tools.get(name);

        if (tool == null) {
            return toolResult("ERROR: unknown tool '" + name + "'.\nNEXT: call tools/list.", true);
        }
        try {
            return toolResult(tool.handler().apply(args), false);
        } catch (Exception e) {
            // Tool failures are returned as isError results, not JSON-RPC errors.
            // A protocol-level error aborts the agent's turn; an isError result lets
            // the model read what went wrong and try something else.
            log("tool '" + name + "' failed: " + e);
            return toolResult("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "\nNEXT: the index may be partial. Fall back to reading files directly.", true);
        }
    }

    private static ObjectNode toolResult(String text, boolean isError) {
        ObjectNode content = M.createObjectNode();
        content.put("type", "text");
        content.put("text", text);

        ArrayNode arr = M.createArrayNode();
        arr.add(content);

        ObjectNode res = M.createObjectNode();
        res.set("content", arr);
        res.put("isError", isError);
        return res;
    }

    // =========================================================================
    // tool registry
    // =========================================================================

    private SpringTools awaitTools() {
        try {
            return toolsFuture.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("index unavailable: " + e.getMessage(), e);
        }
    }

    private void registerTools() {
        register("find_implementations",
                """
                Resolve which concrete Spring bean runs behind a type. Given an interface \
                (or a fuzzy class name), returns candidate beans ordered exactly the way the \
                container resolves them: @Qualifier match, then @Primary, then bean name. \
                Use this whenever you see a field typed as an interface and need the real \
                implementation - there is no textual link between the two.""",
                schema(prop("type", "string",
                                "Interface or class name. Fully-qualified or simple, e.g. 'OwnerService'.", true),
                        prop("qualifier", "string",
                                "The @Qualifier value at the injection point, if any. Changes which candidate wins.", false)),
                args -> awaitTools().findImplementations(
                        str(args, "type"), str(args, "qualifier")));

        register("trace_endpoint",
                """
                Return the full controller -> service -> repository chain for an HTTP route, \
                resolving through interface boundaries at every hop. Use this before editing \
                any request-handling code, to see everything the route touches without \
                opening files one at a time. Returns method signatures only, never bodies.""",
                schema(prop("path", "string",
                                "Route path, e.g. '/owners/{ownerId}'. Partial matches allowed.", true),
                        prop("http_method", "string",
                                "GET, POST, PUT, DELETE, PATCH. Omit to match any.", false),
                        prop("budget_tokens", "integer",
                                "Max tokens of output. Default 800. Raise only if the result was truncated.", false)),
                args -> awaitTools().traceEndpoint(
                        str(args, "path"), str(args, "http_method"), intOrNull(args, "budget_tokens")));

        register("entity_relationships",
                """
                JPA relationship graph for an entity with EFFECTIVE fetch types, including \
                defaults the source never states (@ManyToOne and @OneToOne default to EAGER). \
                Use this before writing a query or a DTO. Reading the entity file alone will \
                make you assume associations are lazy when they are not, which is how N+1 ships.""",
                schema(prop("entity", "string", "Entity class name, e.g. 'Owner'.", true)),
                args -> awaitTools().entityRelationships(str(args, "entity")));

        register("find_callers",
                """
                Inbound call edges for a type or method. Calls made through an interface count \
                as calls to the implementation, which is why grepping the class name undercounts. \
                Use this to assess blast radius before changing a signature.""",
                schema(prop("type", "string", "Class or interface name.", true),
                        prop("method", "string", "Method name. Omit for all methods on the type.", false)),
                args -> awaitTools().findCallers(str(args, "type"), str(args, "method")));

        register("list_beans",
                """
                Inventory of Spring-managed beans, optionally filtered by stereotype or package. \
                Use this to orient in an unfamiliar codebase before drilling in. Note this \
                includes Spring Data repositories, which are bare interfaces carrying no \
                annotation and are invisible to an annotation-based search.""",
                schema(prop("stereotype", "string",
                                "SERVICE, REPOSITORY, CONTROLLER, REST_CONTROLLER, CONFIGURATION, COMPONENT.", false),
                        prop("package", "string", "Package prefix filter.", false)),
                args -> awaitTools().listBeans(str(args, "stereotype"), str(args, "package")));

        register("index_stats",
                "Counts of indexed beans, endpoints, injection points, call edges and JPA relationships. "
                        + "Use to confirm the index covers the project before trusting other results.",
                schema(),
                args -> awaitTools().stats());

        register("expand",
                """
                Source body of a specific method. Every other tool returns signatures only \
                to protect the context window - call this when you actually need to read the \
                implementation of one method surfaced by trace_endpoint or find_callers.""",
                schema(prop("type", "string", "Class name owning the method.", true),
                        prop("method", "string", "Method name.", true),
                        prop("max_lines", "integer", "Cap on lines returned. Default 80.", false)),
                args -> awaitTools().expand(str(args, "type"), str(args, "method"), intOrNull(args, "max_lines")));
    }

    private void register(String name, String description, ObjectNode schema, Function<JsonNode, String> handler) {
        tools.put(name, new ToolDef(name, description, schema, handler));
    }

    // =========================================================================
    // JSON Schema helpers
    // =========================================================================

    private record Prop(String name, String type, String description, boolean required) {}

    private static Prop prop(String name, String type, String description, boolean required) {
        return new Prop(name, type, description, required);
    }

    private static ObjectNode schema(Prop... props) {
        ObjectNode schema = M.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = M.createArrayNode();

        for (Prop p : props) {
            ObjectNode node = properties.putObject(p.name());
            node.put("type", p.type());
            node.put("description", p.description());
            if (p.required()) required.add(p.name());
        }
        schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    // =========================================================================
    // JSON-RPC envelopes
    // =========================================================================

    private static ObjectNode result(JsonNode id, ObjectNode payload) {
        ObjectNode n = M.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id == null ? M.nullNode() : id);
        n.set("result", payload);
        return n;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode err = M.createObjectNode();
        err.put("code", code);
        err.put("message", message);

        ObjectNode n = M.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id == null ? M.nullNode() : id);
        n.set("error", err);
        return n;
    }

    private static String str(JsonNode args, String key) {
        JsonNode v = args.get(key);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Integer intOrNull(JsonNode args, String key) {
        JsonNode v = args.get(key);
        return (v == null || v.isNull()) ? null : v.asInt();
    }

    /** stderr only - stdout is the transport. */
    private static void log(String msg) {
        System.err.println("[" + SERVER_NAME + "] " + msg);
    }
}
