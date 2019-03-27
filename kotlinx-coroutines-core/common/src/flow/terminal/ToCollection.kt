package kotlinx.coroutines.flow.terminal

import kotlinx.coroutines.flow.*

/**
 * Collects given flow into a [destination]
 */
public suspend inline fun <T : Any> Flow<T>.toList(destination: MutableList<T> = ArrayList()): List<T> = toCollection(destination)

/**
 * Collects given flow into a [destination]
 */
public suspend inline fun <T : Any> Flow<T>.toSet(destination: MutableSet<T> = LinkedHashSet()): Set<T> = toCollection(destination)

/**
 * Collects given flow into a [destination]
 */
public suspend inline fun <T : Any, C : MutableCollection<in T>> Flow<T>.toCollection(destination: C): C {
    collect { value ->
        destination.add(value)
    }
    return destination
}
