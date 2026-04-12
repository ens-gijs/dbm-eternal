package io.github.ensgijs.dbm.repository;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a repository API and declares which migration areas it requires.
 * <p>
 * This annotation is <b>required</b> on every {@link Repository} interface that describes
 * a public API contract, even if its migration list is empty. It must only be placed on
 * interfaces (not on abstract classes or concrete implementations).
 * </p>
 * <p>Usage example:</p>
 * <pre>{@code
 * @RepositoryApi("users")
 * public interface UserRepository extends Repository {
 *     List<User> findAll();
 * }
 *
 * // Extending an existing API — inherits "users" migrations by default:
 * @RepositoryApi("premium_users")
 * public interface PremiumUserRepository extends UserRepository {
 *     List<User> findPremium();
 * }
 * }</pre>
 *
 * @see Repository
 * @see io.github.ensgijs.dbm.repository.RepositoryRegistry
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RepositoryApi {

    /**
     * Names of migration areas required by this repository interface (e.g., {@code "users"}, {@code "core"}).
     * May be empty if this interface introduces no new migrations of its own.
     */
    String[] value() default {};

    /**
     * If {@code true} (the default), the migration system will also collect migration names from
     * ancestor {@link Repository} interfaces annotated with {@code @RepositoryApi}, walking up the
     * hierarchy until an interface with {@code inheritMigrations = false} is encountered.
     * Interfaces in the hierarchy that lack this annotation are transparently skipped.
     */
    boolean inheritMigrations() default true;

}