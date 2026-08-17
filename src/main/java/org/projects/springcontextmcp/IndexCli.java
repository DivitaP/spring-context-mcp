package org.projects.springcontextmcp;


import org.projects.springcontextmcp.index.IndexBuilder;
import org.projects.springcontextmcp.index.SpringIndex;
import org.projects.springcontextmcp.tools.SpringTools;

import java.nio.file.Path;

/**
 * Standalone entry point for verifying the scanner against a real repo.
 *
 * Run this before ServerMain exists. If the index is wrong, every downstream
 * measurement in the benchmark is meaningless, and debugging a bad index through
 * the MCP stdio transport is far harder than debugging it here.
 *
 *   ./gradlew indexStats -Pproject=/path/to/spring-petclinic
 */
public final class IndexCli {

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].isBlank()) {
            System.err.println("usage: ./gradlew indexStats -Pproject=/path/to/spring-project");
            System.exit(2);
        }

        Path root = Path.of(args[0]);
        long t0 = System.currentTimeMillis();
        SpringIndex index = new IndexBuilder(root).build();
        long elapsed = System.currentTimeMillis() - t0;

        SpringTools tools = new SpringTools(index);

        System.out.println("indexed " + root + " in " + elapsed + "ms");
        System.out.println(tools.stats());
        System.out.println();

        // Hand-verify these against the source before trusting anything.
        System.out.println("--- beans ---");
        System.out.println(tools.listBeans(null, null));

        System.out.println("--- endpoints (first chain) ---");
        index.endpoints().stream().findFirst().ifPresent(e ->
                System.out.println(tools.traceEndpoint(e.path(), e.httpMethod(), 800)));

        System.out.println("--- endpoint trace ---");
        String path = args.length > 1 ? args[1] : null;
        if (path != null) {
            System.out.println(tools.traceEndpoint(path, null, 800));
        } else {
            // Trace the endpoints most likely to cross a service/repository boundary,
            // not just whichever one the parser happened to see first.
            index.endpoints().stream()
                    .filter(e -> e.httpMethod().equals("GET"))
                    .limit(5)
                    .forEach(e -> System.out.println(tools.traceEndpoint(e.path(), e.httpMethod(), 400)));
        }

        System.out.println("--- tool call trace ---");
        tools.trace().forEach(r ->
                System.out.printf("  %-22s %5d tok  %4d ms%n", r.tool(), r.resultTokens(), r.millis()));
    }
}

