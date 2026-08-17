package org.projects.springcontextmcp.index;

import org.junit.jupiter.api.*;
import org.projects.springcontextmcp.tools.SpringTools;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Structural assertions against spring-petclinic.
 *
 * These exist because two earlier bugs produced plausible-looking output while being
 * wrong: calls resolved to the declaring super-interface instead of the receiver, and
 * chains stopped at the entry method instead of following intra-class delegation.
 * Both looked fine in a stats line. The benchmark rests on this index being correct.
 *
 *   ./gradlew test -Ptarget=/abs/path/to/spring-petclinic
 */
class IndexBuilderTest {

    private static SpringIndex index;
    private static SpringTools tools;

    @BeforeAll
    static void buildIndex() throws Exception {
        String path = System.getProperty("petclinic.path", "");
        assumeTrue(!path.isBlank() && Path.of(path).toFile().isDirectory(),
                "set -Ptarget=/path/to/spring-petclinic to run these");
        index = new IndexBuilder(Path.of(path)).build();
        tools = new SpringTools(index);
    }

    @Test
    void detectsSpringDataRepositoriesWithNoAnnotation() {
        // Bare interfaces extending JpaRepository. Annotation scanning misses these
        // entirely, which would drop the whole persistence layer from the index.
        assertTrue(tools.listBeans("REPOSITORY", null).contains("OwnerRepository"));
        assertTrue(tools.listBeans("REPOSITORY", null).contains("VetRepository"));
    }

    @Test
    void tracesThroughIntraClassDelegation() {
        // showVetList -> findPaginated -> vetRepository.findAll
        String out = tools.traceEndpoint("/vets.html", "GET", 800);
        assertTrue(out.contains("VetRepository"), out);
        assertTrue(out.contains("findAll"), out);
        assertTrue(out.contains("via findPaginated"), out);
    }

    @Test
    void recordsReceiverTypeNotDeclaringType() {
        // findAll is declared on PagingAndSortingRepository. Reporting that instead of
        // VetRepository tells an agent nothing about what the route touches.
        String out = tools.traceEndpoint("/vets.html", "GET", 800);
        assertFalse(out.contains("PagingAndSortingRepository"), out);
    }

    @Test
    void surfacesModelAttributeMethodsBeforeHandler() {
        // initUpdateOwnerForm takes no args and appears to touch nothing, but
        // @ModelAttribute findOwner already loaded the owner from the repository.
        String out = tools.traceEndpoint("/owners/{ownerId}/edit", "GET", 800);
        assertTrue(out.contains("BEFORE HANDLER"), out);
        assertTrue(out.contains("OwnerRepository"), out);
    }

    @Test
    void reportsEffectiveFetchTypeIncludingDefaults() {
        String out = tools.entityRelationships("Owner");
        assertTrue(out.contains("ONE_TO_MANY"), out);
        assertTrue(out.contains("pets"), out);
    }

    @Test
    void respectsTokenBudget() {
        String out = tools.traceEndpoint("/owners", null, 50);
        assertTrue(out.length() < 50 * 6, "budget ignored: " + out.length() + " chars");
    }
}
