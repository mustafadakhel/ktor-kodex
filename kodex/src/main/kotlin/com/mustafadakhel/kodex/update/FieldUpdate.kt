package com.mustafadakhel.kodex.update

/**
 * Represents an explicit update operation on a field.
 * Solves the ambiguous null problem by distinguishing between:
 * - NoChange: Field should not be modified
 * - SetValue: Field should be set to a specific value
 * - ClearValue: Field should be set to null
 */
public sealed interface FieldUpdate<out T> {
    /**
     * Indicates that the field should not be modified.
     */
    public class NoChange<T> : FieldUpdate<T> {
        override fun equals(other: Any?): Boolean = other is NoChange<*>
        override fun hashCode(): Int = 0
        override fun toString(): String = "NoChange"
    }

    /**
     * Indicates that the field should be set to the specified value.
     */
    public data class SetValue<T>(val value: T) : FieldUpdate<T>

    /**
     * Indicates that the field should be cleared (set to null).
     */
    public class ClearValue<T> : FieldUpdate<T> {
        override fun equals(other: Any?): Boolean = other is ClearValue<*>
        override fun hashCode(): Int = 1
        override fun toString(): String = "ClearValue"
    }
}

/**
 * Converts a value to a SetValue field update.
 */
public fun <T> T.asUpdate(): FieldUpdate<T> = FieldUpdate.SetValue(this)

/**
 * Creates a ClearValue field update.
 */
public fun <T> clearField(): FieldUpdate<T> = FieldUpdate.ClearValue()

/**
 * Creates a NoChange field update.
 */
public fun <T> noChange(): FieldUpdate<T> = FieldUpdate.NoChange()

/**
 * Maps the value inside a FieldUpdate if it's a SetValue.
 */
public inline fun <T, R> FieldUpdate<T>.map(transform: (T) -> R): FieldUpdate<R> = when (this) {
    is FieldUpdate.NoChange -> FieldUpdate.NoChange()
    is FieldUpdate.SetValue -> FieldUpdate.SetValue(transform(value))
    is FieldUpdate.ClearValue -> FieldUpdate.ClearValue()
}

/**
 * Returns the value if SetValue, otherwise null.
 */
public fun <T> FieldUpdate<T>.valueOrNull(): T? = when (this) {
    is FieldUpdate.SetValue -> value
    else -> null
}

/**
 * Returns true if this field update represents an actual change (not NoChange).
 */
public fun <T> FieldUpdate<T>.hasChange(): Boolean = this !is FieldUpdate.NoChange
