package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.sql.SqlDialect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which SQL dialects a concrete {@link Repository} implementation supports.
 * <p>
 * This annotation is <b>required</b> on every concrete class that implements a
 * {@link RepositoryApi @RepositoryApi}-annotated interface. It must declare at least one
 * {@link SqlDialect} and must not include {@link SqlDialect#UNDEFINED}.
 * </p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * @RepositoryImpl(dialects = {SqlDialect.MYSQL, SqlDialect.SQLITE})
 * public class PlayerStatsRepositoryImpl implements PlayerStatsRepository {
 *     ...
 * }
 * }</pre>
 *
 * <h2>Registry integration</h2>
 * <p>
 * At {@link RepositoryRegistry#closeRegistration()} time, the registry filters impl candidates
 * by the nominating provider's active dialect. Only impls whose declared dialects include the
 * provider's active dialect are eligible to win the binding contest.
 * </p>
 *
 * <h2>Placement rules</h2>
 * <ul>
 * <li>Required on concrete classes that implement a {@code @RepositoryApi} interface.</li>
 * <li>Not applied to {@link RepositoryComposition} concretions.</li>
 * <li>The class hierarchy is not scanned; the annotation must be placed directly on the concretion.</li>
 * </ul>
 *
 * <p>Static helpers for reading this annotation: {@link Repository#supportedDialectsOf(Class)}
 * and {@link Repository#supportsDialect(Class, SqlDialect)}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RepositoryImpl {

    /**
     * SQL dialects this concretion supports. Must be non-empty and must not contain
     * {@link SqlDialect#UNDEFINED}. Read at registry closeRegistration time to filter
     * candidate impls by the nominating provider's active dialect.
     */
    SqlDialect[] dialects();
}
