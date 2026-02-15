package com.mustafadakhel.kodex.update

public sealed interface FieldUpdate<out T> {
    public data object NoChange : FieldUpdate<Nothing>

    public data class SetValue<T>(val value: T) : FieldUpdate<T>

    public data object ClearValue : FieldUpdate<Nothing>
}

public fun <T> T.asUpdate(): FieldUpdate<T> = FieldUpdate.SetValue(this)

public fun <T> clearField(): FieldUpdate<T> = FieldUpdate.ClearValue

public fun <T> noChange(): FieldUpdate<T> = FieldUpdate.NoChange

public inline fun <T, R> FieldUpdate<T>.map(transform: (T) -> R): FieldUpdate<R> = when (this) {
    is FieldUpdate.NoChange -> FieldUpdate.NoChange
    is FieldUpdate.SetValue -> FieldUpdate.SetValue(transform(value))
    is FieldUpdate.ClearValue -> FieldUpdate.ClearValue
}

public fun <T> FieldUpdate<T>.valueOrNull(): T? = when (this) {
    is FieldUpdate.SetValue -> value
    else -> null
}

public fun <T> FieldUpdate<T>.hasChange(): Boolean = this !is FieldUpdate.NoChange
