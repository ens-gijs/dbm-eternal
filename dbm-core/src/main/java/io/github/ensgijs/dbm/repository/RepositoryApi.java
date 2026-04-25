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
 * interfaces that <b>directly extend</b> {@link Repository} — extending other
 * {@code @RepositoryApi} interfaces is forbidden.
 * </p>
 * <p>Usage example:</p>
 * <pre>{@code
 * @RepositoryApi("users")
 * public interface UserRepository extends Repository {
 *     List<User> findAll();
 * }
 * }</pre>
 *
 * <p>Supported SQL dialects belong on the concrete implementation class via
 * {@link RepositoryImpl @RepositoryImpl}, not here. Dialect support is an impl concern —
 * placing it on the API would force every implementor to cover the full declared set.</p>
 *
 * @see Repository
 * @see RepositoryImpl
 * @see RepositoryRegistry
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RepositoryApi {

    /**
     * Names of migration areas required by this repository interface (e.g., {@code "users"}, {@code "core"}).
     * May be empty if this interface introduces no new migrations of its own.
     */
    String[] value() default {};

}
