package io.github.ensgijs.dbm.repository;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a repository api and declares which migrations are required for it.
 * <p>This annotation is REQUIRED, even if it's list of migrations is empty, on all {@link Repository}
 * interfaces which describe the API of a repository.</p>
 * @see Repository
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RepositoryApi {

    /**
     * Required migration names (may be empty). The names of the functional areas to migrate (e.g., "users", "core").
     */
    String[] value() default "";

    /**
     * If true (default), the migrator will also look for this annotation on parent interfaces
     * and include their migration names. If an interface in the hierarchy lacks the MigrationName annotation
     * it will be traversed as if inherit itself was inherited.
     */
    boolean inheritMigrations() default true;

}