package com.mustafadakhel.kodex.model

public data class Page<T>(
    val items: List<T>,
    val totalCount: Long,
    val offset: Int,
    val limit: Int,
) {
    val hasMore: Boolean get() = offset + items.size < totalCount
}
