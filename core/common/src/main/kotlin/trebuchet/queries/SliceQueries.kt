/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package trebuchet.queries

import trebuchet.model.Model
import trebuchet.model.ProcessModel
import trebuchet.model.ThreadModel
import trebuchet.model.base.Slice
import trebuchet.model.base.SliceGroup
import kotlin.coroutines.experimental.SequenceBuilder
import kotlin.coroutines.experimental.buildSequence

enum class TraverseAction {
    /**
     * Continue iterating over any child slices
     */
    VISIT_CHILDREN,
    /**
     * There is no need to process any child slices
     */
    DONE,
}

interface SliceTraverser {
    fun beginSlice(slice: Slice): TraverseAction = TraverseAction.VISIT_CHILDREN
    fun endSlice(slice: Slice) {}
}

/**
 * SliceQueries provides several methods of operating over all the slices in a model in bulk.
 *
 * [selectAll] finds all slices that match the given predicate.
 *
 * [iterSlices] applies a function to all slices in a model.
 *
 * [traverseSlices] is a more powerful version of [iterSlices]. It takes a [SliceTraverser], which
 * allows code to be run at the beginning and end of processing a slice. This allows the
 * [SliceTraverser] to, for example, keep track of how deep it is within the tree.
 * The [SliceTraverser] can also indicate whether [traverseSlices] should continue processing
 * child slices in the case of a [SliceGroup].
 */
object SliceQueries {
    fun selectAll(model: Model, cb: (Slice) -> Boolean): List<Slice> {
        val ret = mutableListOf<Slice>()
        iterSlices(model) {
            if (cb(it)) {
                ret.add(it)
            }
        }
        return ret
    }

    fun selectAll(thread: ThreadModel, cb: (Slice) -> Boolean): List<Slice> {
        val ret = mutableListOf<Slice>()
        iterSlices(thread) {
            if (cb(it)) {
                ret.add(it)
            }
        }
        return ret
    }

    fun traverseSlices(model: Model, cb: SliceTraverser) {
        model.processes.values.forEach { traverseSlices(it, cb) }
    }

    fun traverseSlices(process: ProcessModel, cb: SliceTraverser) {
        process.threads.forEach { traverseSlices(it, cb) }
    }

    fun traverseSlices(thread: ThreadModel, cb: SliceTraverser) {
        traverseSlices(thread.slices, cb)
    }

    fun traverseSlices(slices: List<SliceGroup>, cb: SliceTraverser) {
        slices.forEach {
            val action = cb.beginSlice(it)
            if (action == TraverseAction.VISIT_CHILDREN)
                traverseSlices(it.children, cb)
            cb.endSlice(it)
        }
    }

    fun iterSlices(model: Model, cb: (Slice) -> Unit) {
        model.processes.values.forEach { iterSlices(it, cb) }
    }

    fun iterSlices(process: ProcessModel, cb: (Slice) -> Unit) {
        process.threads.forEach { iterSlices(it, cb) }
    }

    fun iterSlices(thread: ThreadModel, cb: (Slice) -> Unit) {
        iterSlices(thread.slices, cb)
    }

    fun iterSlices(slices: List<SliceGroup>, cb: (Slice) -> Unit) {
        slices.forEach {
            cb(it)
            iterSlices(it.children, cb)
        }
    }

    fun any(slices: List<SliceGroup>, cb: (Slice) -> Boolean): Boolean {
        slices.forEach {
            if (cb(it)) return true
            if (any(it.children, cb)) return true
        }
        return false
    }
}

private suspend fun SequenceBuilder<Slice>.yieldSlices(slices: List<SliceGroup>) {
    slices.forEach {
        yield(it)
        yieldSlices(it.children)
    }
}

fun Model.slices(includeAsync: Boolean = true): Sequence<Slice> {
    val model = this
    return buildSequence {
        model.processes.values.forEach { process ->
            if (includeAsync) {
                yieldAll(process.asyncSlices)
            }
            process.threads.forEach { thread ->
                yieldSlices(thread.slices)
            }
        }
    }
}