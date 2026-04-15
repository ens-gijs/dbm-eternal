package io.github.ensgijs.dbm.repository;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Modifier;

/**
 * Use {@code RepositoryComposition}'s to contain the business logic required to coordinate actions across
 * multiple {@link io.github.ensgijs.dbm.repository.Repository}'s.
 *
 * <p>
 * {@link RepositoryRegistry} offers facilities to flyweight composition instances making shared instances globally
 * available (effectively singleton). Two registration lineages are supported:
 * </p>
 * <ul>
 * <li><b>Direct</b> — {@code class MyComp implements RepositoryComposition}. Looked up by the concrete class.</li>
 * <li><b>Replaceable</b> — {@code class MyComp extends AbstractMyComp} where {@code AbstractMyComp} is abstract
 *     and implements {@code RepositoryComposition}. Multiple implementations may contest for the abstract key;
 *     the winner is determined at {@link RepositoryRegistry#closeRegistration()} time. Looked up by the abstract
 *     class only — passing the concrete class to {@link RepositoryRegistry#getCompositeRepository(Class)} is an error.</li>
 * </ul>
 * <p>
 * Implementors must define a constructor taking only a {@link RepositoryRegistry}, a default no-args constructor,
 * or pre-register a creator with {@code RepositoryRegistry#registerComposition} to be able to make use
 * of {@link RepositoryRegistry#getCompositeRepository(Class)}.
 * </p>
 * <p>
 * {@link RepositoryComposition}'s may not possess the {@link RepositoryApi} annotation, implement {@link Repository},
 * or own tables directly.
 * </p>
 * @apiNote <b>Best Practices:</b>
 * <ul>
 * <li>{@link RepositoryComposition}'s must NOT call {@link RepositoryRegistry#getCompositeRepository(Class)} from
 * their constructor, they should wait until {@link #onInitialize(RepositoryRegistry)} is called.
 * <li>{@link RepositoryComposition}'s should use the passed {@link RepositoryRegistry} instead of accessing the
 * singleton {@link RepositoryRegistry#globalRegistry()}.
 * </ul>
 */
public interface RepositoryComposition {
    /**
     * Phase 2 of the create-instantiate pattern. It is safe to call {@link RepositoryRegistry#getCompositeRepository(Class)}
     * from within this handler but not guaranteed safe to use the returned result due to the risk of bidirectional
     * dependency.
     */
    default void onInitialize(@NotNull RepositoryRegistry registry) throws RepositoryInitializationException {}

    /**
     * Returns the abstract key (slot) that {@code compositionType} is registered under, or
     * {@code null} if it is a direct non-replaceable concrete type.
     * <p>
     * Three behaviours depending on the kind of class passed:
     * </p>
     * <ul>
     * <li><b>Abstract class</b> — it is itself the replaceable slot; returns {@code compositionType}.</li>
     * <li><b>Concrete, direct</b> ({@code MyComp implements RepositoryComposition}) — returns {@code null}.</li>
     * <li><b>Concrete, replaceable</b> ({@code MyImpl extends AbstractBase}) — returns {@code AbstractBase}.</li>
     * </ul>
     * @param compositionType A non-interface {@code RepositoryComposition} class (abstract or concrete).
     * @return The abstract intermediary (replaceable slot key), or {@code null} for direct concrete types.
     * @throws IllegalArgumentException If {@code compositionType} is an interface; if a concrete type
     *     extends another concrete {@code RepositoryComposition}; or if the abstract intermediary
     *     chain is deeper than one level.
     */
    @SuppressWarnings("unchecked")
    static @Nullable Class<? extends RepositoryComposition> identifyAbstractKey(
            @NotNull Class<? extends RepositoryComposition> compositionType) {
        if (compositionType.isInterface()) {
            throw new IllegalArgumentException("compositionType must not be an interface: "
                    + compositionType.getName());
        }
        // Abstract class is itself the replaceable slot key.
        if (Modifier.isAbstract(compositionType.getModifiers())) {
            return compositionType;
        }
        // Concrete type: walk the superclass chain to find the optional abstract intermediary.
        Class<? extends RepositoryComposition> abstractIntermediary = null;
        Class<?> current = compositionType.getSuperclass();
        while (current != null && RepositoryComposition.class.isAssignableFrom(current) && !current.isInterface()) {
            if (Modifier.isAbstract(current.getModifiers())) {
                if (abstractIntermediary != null) {
                    throw new IllegalArgumentException(
                            "compositionType " + compositionType.getName()
                                    + " has a superclass chain deeper than one abstract intermediary. "
                                    + "Only a single abstract intermediary between the concrete type and RepositoryComposition is allowed.");
                }
                abstractIntermediary = (Class<? extends RepositoryComposition>) current;
            } else {
                // Concrete superclass — extending a concrete composition is illegal.
                throw new IllegalArgumentException(
                        "compositionType " + compositionType.getName()
                                + " extends a concrete RepositoryComposition (" + current.getName() + "). "
                                + "Extending a concrete composition is not allowed.");
            }
            current = current.getSuperclass();
        }
        return abstractIntermediary;
    }

    /**
     * Validates that {@code compositionType} is a concrete class conforming to one of the two
     * valid {@link RepositoryComposition} lineages. Throws if the type is abstract, an interface,
     * or has an invalid superclass chain.
     * @throws IllegalArgumentException If {@code compositionType} is abstract or an interface,
     *     or if its lineage is invalid (see {@link #identifyAbstractKey(Class)}).
     */
    static void validateConcreteCompositionType(
            @NotNull Class<? extends RepositoryComposition> compositionType) {
        if (compositionType.isInterface() || Modifier.isAbstract(compositionType.getModifiers())) {
            throw new IllegalArgumentException(
                    "compositionType must be a concrete (non-abstract, non-interface) class: "
                    + compositionType.getName());
        }
        identifyAbstractKey(compositionType); // validates the chain; return value discarded
    }
}
