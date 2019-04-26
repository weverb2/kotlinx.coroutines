/*
 * Copyright 2016-2019 JetBrains s.r.o. and contributors Use of this source code is governed by the Apache 2.0 license.
 */

package benchmarks.flow.scrabble

import io.reactivex.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.openjdk.jmh.annotations.*
import java.lang.Long.*
import java.util.*
import java.util.concurrent.*
import java.util.stream.*

@Warmup(iterations = 7, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 7, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
open class FlowPlaysScrabbleOpt : ShakespearePlaysScrabble() {

    @Benchmark
    public override fun play(): List<Map.Entry<Int, List<String>>> {
        val toIntegerStream = { string: String -> string.chars().asFlow() }

        val histoCache = HashMap<String, Flow<Map<Int, MutableLong>>>()
        val histoOfLetters = { word: String ->
            var result = histoCache[word]
            if (result == null) {
                val f = flow<Map<Int, MutableLong>> {
                    val r = toIntegerStream(word).fold(HashMap<Int, MutableLong>()) { accumulator, value ->
                        var newValue: MutableLong? = accumulator[value]
                        if (newValue == null) {
                            newValue = MutableLong()
                            accumulator[value] = newValue
                        }
                        newValue.incAndSet()
                        accumulator
                    }

                    emit(r)
                }

                result = f
                histoCache[word] = result
            }
            result!!
        }

        val blank = { entry: Map.Entry<Int, MutableLong> ->
            max(0L, entry.value.get() - scrabbleAvailableLetters[entry.key - 'a'.toInt()])
        }

        val nBlanks = { word: String ->
            flow {
                val r = histoOfLetters(word)
                    .flatMapConcatIterable { it.entries }
                    .map({ blank(it) })
                    .sum()
                emit(r)
            }
        }

        val checkBlanks = { word: String ->
            nBlanks(word).map { it <= 2L }
        }

        val letterScore = { entry: Map.Entry<Int, MutableLong> ->
                letterScores[entry.key - 'a'.toInt()] * Integer.min(
                    entry.value.get().toInt(),
                    scrabbleAvailableLetters[entry.key - 'a'.toInt()]
                )
        }

        val score2 =  { word: String ->
            flow {
                emit((histoOfLetters(word)
                    .flatMapConcatIterable { it.entries }
                    .map { letterScore(it) }
                    .sum()))
            }
        }

        val first3 = { word: String -> word.asFlow(endIndex = 3) }
        val last3 = { word: String -> word.asFlow(startIndex = 3) }
        val toBeMaxed = { word: String -> concat(first3(word), last3(word)) }

        // Bonus for double letter
        val bonusForDoubleLetter = { word: String ->
           flow {
                emit(toBeMaxed(word)
                    .map { letterScores[it.toInt() - 'a'.toInt()] }
                    .max())
            }
        }

        val score3 = { word: String ->
            flow {
                val sum = score2(word).single() + bonusForDoubleLetter(word).single()
                emit(sum * 2 + if (word.length == 7) 50 else 0)
            }
        }

        val buildHistoOnScore: (((String) -> Flow<Int>) -> Flow<TreeMap<Int, List<String>>>) = { score ->
            flow {
                val r = shakespeareWords.iterator().asFlow()
                    .filter({ scrabbleWords.contains(it) && checkBlanks(it).single() })
                    .fold(TreeMap<Int, List<String>>(Collections.reverseOrder())) { acc, value ->
                        val key = score(value).single()
                        var list = acc[key] as MutableList<String>?
                        if (list == null) {
                            list = ArrayList()
                            acc[key] = list
                        }
                        list.add(value)
                        acc
                    }
                emit(r)
            }
        }

        return runBlocking {
            buildHistoOnScore(score3)
                .flatMapConcatIterable { it.entries }
                .take(3)
                .toList()
        }
    }

    // Default flow that is
    private inline fun <T> flow(@BuilderInference crossinline block: suspend FlowCollector<T>.() -> Unit): Flow<T> {
        return object : Flow<T> {
            override suspend fun collect(collector: FlowCollector<T>) {
                collector.block()
            }
        }
    }
}

public suspend fun Flow<Int>.sum(): Int {
    val collector = object : FlowCollector<Int> {
        @JvmField
        public var sum = 0

        override suspend fun emit(value: Int) {
            sum += value
        }
    }
    collect(collector)
    return collector.sum
}

public suspend fun Flow<Int>.max(): Int {
    val collector = object : FlowCollector<Int> {
        @JvmField
        public var max = 0

        override suspend fun emit(value: Int) {
            max = Math.max(max, value)
        }
    }
    collect(collector)
    return collector.max
}

@JvmName("longSum")
public suspend fun Flow<Long>.sum(): Long {
    val collector = object : FlowCollector<Long> {
        @JvmField
        public var sum = 0L

        override suspend fun emit(value: Long) {
            sum += value
        }
    }
    collect(collector)
    return collector.sum
}

public fun String.asFlow(startIndex: Int = 0, endIndex: Int = length) =
    StringByCharFlow(this, startIndex, endIndex.coerceAtMost(this.length))

public class StringByCharFlow(private val source: String, private val startIndex: Int, private val endIndex: Int): Flow<Char> {
    override suspend fun collect(collector: FlowCollector<Char>) {
        for (i in startIndex until endIndex) collector.emit(source[i])
    }
}

public fun <T> concat(first: Flow<T>, second: Flow<T>): Flow<T> = flow {
    first.collect { value ->
        emit(value)
    }

    second.collect { value ->
        emit(value)
    }
}

public fun <T, R> Flow<T>.flatMapConcatIterable(transformer: (T) -> Iterable<R>): Flow<R> =
    flow {
        collect { value ->
            transformer(value).forEach { r ->
                emit(r)
            }
        }
    }

public fun IntStream.asFlow(): Flow<Int> = flow {
    val iterator = Spliterators.iterator(spliterator())
    while (iterator.hasNext()) {
        emit(iterator.next())
    }
}