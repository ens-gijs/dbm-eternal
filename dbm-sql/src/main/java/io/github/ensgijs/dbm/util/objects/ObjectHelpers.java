package io.github.ensgijs.dbm.util.objects;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Utility class providing null-safe operations for type casting,
 * Enum parsing, and value coalescing.
 */
public final class ObjectHelpers {
    private ObjectHelpers() {}

/**
 * Performs a safe-cast operation that won't throw if {@code obj} is not of type {@code clazz}.
 *
 * @param <T>   The target type.
 * @param obj   The object to cast.
 * @param clazz The class type to cast to.
 * @return The object cast to type T, or {@code null} if the object is null
 * or not an instance of the specified class.
 */
    @Contract("null, _ -> null")
    public static <T> @Nullable T as(@Nullable Object obj, @NotNull Class<T> clazz) {
        return clazz.isInstance(obj) ? clazz.cast(obj) : null;
    }

    /**
     * Attempts to parse a string into an Enum constant of the same type as the provided default.
     * <p>
     * The lookup is case-insensitive as the input string is converted to uppercase
     * using {@link Locale#ENGLISH}.
     * @param value        The string name of the enum constant.
     * @param defaultValue The value to return if the input is null, empty, or not found.
     * @param <E>          The Enum type.
     * @return The matching Enum constant, or {@code defaultValue} if no match is found.
     * @throws IllegalArgumentException if the {@code defaultValue} does not belong to an Enum class.
     *   Does not throw if given value is not defined on the enum.
     */
    @Contract("null, _ -> param2")
    public static <E extends Enum<E>> E asEnum(@Nullable String value, @NotNull E defaultValue) {
        if (value == null || value.isEmpty()) return defaultValue;
        value = value.toUpperCase(Locale.ENGLISH);
        try {
            return E.valueOf(defaultValue.getDeclaringClass(), value);
        } catch (IllegalArgumentException ex) {
            return defaultValue;
        }
    }

    /**
     * Attempts to parse a string into an Enum constant of the specified class. Does not throw.
     * @param value The string name of the enum constant.
     * @param clazz The Enum class to search in.
     * @param <E>   The Enum type.
     * @return The matching Enum constant, or {@code null} if the input is null,
     * empty, or the constant does not exist.
     */
    @Contract("null, _ -> null")
    public static <E extends Enum<E>> E asEnum(String value, Class<E> clazz) {
        if (value == null || value.isEmpty()) return null;
        value = value.toUpperCase(Locale.ENGLISH);
        try {
            return E.valueOf(clazz, value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Returns the first parameter if it is not null, otherwise returns the second.
     * This is a functional equivalent to the C# ?? operator or SQL COALESCE.
     * @param first  The primary value.
     * @param second The fallback value.
     * @param <T>    The type of the values.
     * @return {@code first} if non-null; {@code second} otherwise.
     */
    @Contract("!null, _ -> param1; null, _ -> param2")
    public static <T> T coalesce(T first, T second) {
        return first != null ? first : second;
    }

    /**
     * Returns the first non-null value from a sequence of arguments.
     * @param values The values to check.
     * @param <T>    The type of the values.
     * @return The first non-null value in the array, or {@code null} if all values
     * (or the array itself) are null.
     */
    @SafeVarargs
    public static <T> T coalesce(T ... values) {
        return Arrays.stream(values).filter(Objects::nonNull).findFirst().orElse(null);
    }
}
