package io.github.ensgijs.dbm.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the static contract-enforcing methods on {@link Repository}:
 * {@link Repository#identifyRepositoryApi}, {@link Repository#validateRepositoryApi},
 * and {@link Repository#collectMigrationNames}.
 */
public class RepositoryContractTest {

    // -----------------------------------------------------------------------
    // Test hierarchy — valid cases
    // -----------------------------------------------------------------------

    @RepositoryApi("items")
    interface ItemRepo extends Repository {}

    @RepositoryApi({"orders", "order_items"})
    interface OrderRepo extends Repository {}

    @RepositoryApi
    interface NoMigrationRepo extends Repository {}

    static class ItemRepoImpl extends AbstractRepository implements ItemRepo {
        public ItemRepoImpl(io.github.ensgijs.dbm.sql.SqlClient c) { super(c); }
        @Override public void invalidateCaches() { onCacheInvalidatedEvent.accept(this); }
    }

    // -----------------------------------------------------------------------
    // Test hierarchy — invalid cases
    // -----------------------------------------------------------------------

    /** No @RepositoryApi anywhere in the hierarchy. */
    interface NoAnnotationRepo extends Repository {}

    static class NoAnnotationImpl extends AbstractRepository implements NoAnnotationRepo {
        public NoAnnotationImpl(io.github.ensgijs.dbm.sql.SqlClient c) { super(c); }
    }

    /** @RepositoryApi on a non-Repository interface — invalid. */
    @RepositoryApi("bad")
    interface NotARepositoryIface {}

    /** @RepositoryApi on a concrete class — invalid. */
    @RepositoryApi("bad")
    static class AnnotatedConcreteClass implements Repository {
        @Override public void invalidateCaches() {}
        @Override public io.github.ensgijs.dbm.util.objects.SubscribableEvent<Repository> onCacheInvalidatedEvent() { return null; }
    }

    /** @RepositoryApi that extends another @RepositoryApi — chain prohibited. */
    @RepositoryApi("parent")
    interface ParentApi extends Repository {}

    @RepositoryApi("child")
    interface ChildApi extends ParentApi {}   // invalid: extends another @RepositoryApi

    static class ChildImpl extends AbstractRepository implements ChildApi {
        public ChildImpl(io.github.ensgijs.dbm.sql.SqlClient c) { super(c); }
    }

    /** @RepositoryApi that does NOT directly extend Repository (extends a plain interface). */
    interface PlainIface {}

    @RepositoryApi("indirect")
    interface IndirectApi extends PlainIface, Repository {} // Does directly extend Repository, so this is actually valid

    /** Impl that implements two separate @RepositoryApi interfaces — ambiguous. */
    static class DualApiImpl extends AbstractRepository implements ItemRepo, OrderRepo {
        public DualApiImpl(io.github.ensgijs.dbm.sql.SqlClient c) { super(c); }
    }

    // -----------------------------------------------------------------------
    // identifyRepositoryApi
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Repository.identifyRepositoryApi")
    class IdentifyRepositoryApiTest {

        @Test
        @DisplayName("Returns api interface when called on a valid impl class")
        void implClass_returnsApiInterface() {
            assertSame(ItemRepo.class, Repository.identifyRepositoryApi(ItemRepoImpl.class));
        }

        @Test
        @DisplayName("Returns itself when called on a valid @RepositoryApi interface")
        void apiInterface_returnsSelf() {
            assertSame(ItemRepo.class, Repository.identifyRepositoryApi(ItemRepo.class));
        }

        @Test
        @DisplayName("Throws when no @RepositoryApi is present in the hierarchy")
        void noAnnotation_throws() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> Repository.identifyRepositoryApi(NoAnnotationImpl.class));
            assertTrue(ex.getMessage().contains("@RepositoryApi"), ex.getMessage());
        }

        @Test
        @DisplayName("Throws when impl implements multiple @RepositoryApi interfaces")
        void multipleApis_throws() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> Repository.identifyRepositoryApi(DualApiImpl.class));
            assertTrue(ex.getMessage().contains("multiple"), ex.getMessage());
        }

        @Test
        @DisplayName("Throws when a @RepositoryApi interface extends another @RepositoryApi")
        void apiChain_throws() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> Repository.identifyRepositoryApi(ChildImpl.class));
            assertTrue(ex.getMessage().toLowerCase().contains("chain") ||
                       ex.getMessage().toLowerCase().contains("extends another"), ex.getMessage());
        }

        @Test
        @DisplayName("Throws when @RepositoryApi is placed on a non-Repository interface")
        void annotationOnNonRepositoryInterface_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> Repository.identifyRepositoryApi((Class<Repository>) (Class<?>) NotARepositoryIface.class));
        }

        @Test
        @DisplayName("Throws when @RepositoryApi is placed on a concrete class")
        void annotationOnConcreteClass_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> Repository.identifyRepositoryApi(AnnotatedConcreteClass.class));
        }
    }

    // -----------------------------------------------------------------------
    // validateRepositoryApi
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Repository.validateRepositoryApi")
    class ValidateRepositoryApiTest {

        @Test
        @DisplayName("Passes silently for a valid @RepositoryApi interface")
        void validApi_passes() {
            assertDoesNotThrow(() -> Repository.validateRepositoryApi(ItemRepo.class));
        }

        @Test
        @DisplayName("Throws when called with a concrete class instead of an interface")
        void concreteClass_throws() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> Repository.validateRepositoryApi((Class<Repository>) (Class<?>) ItemRepoImpl.class));
            assertTrue(ex.getMessage().contains("interface"), ex.getMessage());
        }

        @Test
        @DisplayName("Throws when interface has no @RepositoryApi annotation")
        void missingAnnotation_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> Repository.validateRepositoryApi(NoAnnotationRepo.class));
        }

        @Test
        @DisplayName("Throws when @RepositoryApi interface extends another @RepositoryApi")
        void chainedApi_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> Repository.validateRepositoryApi(ChildApi.class));
        }
    }

    // -----------------------------------------------------------------------
    // collectMigrationNames
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Repository.collectMigrationNames")
    class CollectMigrationNamesTest {

        @Test
        @DisplayName("Returns all declared migration area names")
        void withNames_returnsNames() {
            List<String> names = Repository.collectMigrationNames(OrderRepo.class);
            assertEquals(List.of("orders", "order_items"), names);
        }

        @Test
        @DisplayName("Returns empty list when @RepositoryApi has no value")
        void noNames_returnsEmpty() {
            assertTrue(Repository.collectMigrationNames(NoMigrationRepo.class).isEmpty());
        }

        @Test
        @DisplayName("Returns single-element list for single migration area")
        void singleName_returnsSingletonList() {
            List<String> names = Repository.collectMigrationNames(ItemRepo.class);
            assertEquals(List.of("items"), names);
        }

        @Test
        @DisplayName("Throws when called on a non-annotated interface")
        void noAnnotation_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> Repository.collectMigrationNames(NoAnnotationRepo.class));
        }
    }
}
