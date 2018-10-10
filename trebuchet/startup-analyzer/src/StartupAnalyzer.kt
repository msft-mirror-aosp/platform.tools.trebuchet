/*
 * Copyright 2018 Google Inc.
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

/*
 * Notes
 *
 * TODO (chriswailes): Support JSON output
 */

/*
 * Imports
 */

import java.io.File
import trebuchet.model.Model
import trebuchet.extras.parseTrace
import trebuchet.model.base.Slice
import trebuchet.queries.SliceQueries
import trebuchet.queries.SliceTraverser
import trebuchet.queries.TraverseAction
import kotlin.system.exitProcess

/*
 * Constants
 */

/*
 * Class Definition
 */

/*
 * Class Extensions
 */

//private fun Double.secondValueToMillisecondString() = "%.3f ms".format(this * 1000.0)

private fun <K, V> MutableMap<K, MutableList<V>>.add(key: K, el: V) {
    this.getOrPut(key) { mutableListOf() }.add(el)
}

/*
 * Helper Functions
 */

fun measureStartup(model: Model) {
    val systemServerIDs = model.findIDsByName(PROC_NAME_SYSTEM_SERVER)

    if (systemServerIDs == null) {
        println("Error: Unable to find the System Server")
        exitProcess(1)
    }

    val systemServerProc = model.processes[systemServerIDs.first]
    val startedApps: MutableMap<Int, Pair<String, Double>> = mutableMapOf()

    systemServerProc!!.threads.forEach { thread ->
        SliceQueries.iterSlices(thread) { slice ->
            if (slice.name.startsWith(SLICE_NAME_PROC_START)) {

                val newProcName = slice.name.split(':', limit = 2)[1].trim()
                val newProcIDs = model.findIDsByName(newProcName)

                when {
                    newProcIDs == null -> println("Unable to find PID for process `$newProcName`")
                    startedApps.containsKey(newProcIDs.first) -> println("PID already mapped to started app")
                    else -> startedApps[newProcIDs.first] = Pair(newProcName, slice.startTime)
                }
            }
        }
    }

    startedApps.forEach { pid, (startedName, startupStartTime) ->
        val process = model.processes[pid]
        val mainThread = process!!.threads[0]

        val startupEndTime = model.getStartupEndTime(mainThread, startupStartTime)
        var unallocatedTime = 0.0

        val topLevelSlices = mutableMapOf<String, MutableList<Double>>()

        // Triple := Duration, Duration Self, Payload
        val allSlices = mutableMapOf<String, MutableList<Triple<Double, Double, String?>>>()

        SliceQueries.traverseSlices(mainThread, object : SliceTraverser {
            // Our depth down an individual tree in the slice forest.
            var treeDepth = -1

            var lastTopLevelSlice : Slice? = null

            override fun beginSlice(slice: Slice): TraverseAction {
                ++this.treeDepth

                if (slice.startTime < startupEndTime.timestamp) {
                    // This slice starts during the startup period.  If it
                    // ends within the startup period we will record info
                    // from this slice.  Either way we will visit its
                    // children.

                    if (this.treeDepth == 0 && this.lastTopLevelSlice != null) {
                        unallocatedTime += (slice.startTime - this.lastTopLevelSlice!!.endTime)
                    }

                    if (slice.endTime <= startupEndTime.timestamp) {

                        val sliceInfo = parseSliceInfo(slice.name)

                        allSlices.add(sliceInfo.type, Triple(slice.duration, slice.durationSelf, sliceInfo.payload))
                        if (this.treeDepth == 0) topLevelSlices.add(sliceInfo.type, slice.duration)
                    }

                    return TraverseAction.VISIT_CHILDREN

                } else {
                    // All contents of this slice occur after the startup
                    // period has ended. We don't need to record anything
                    // or traverse any children.
                    return TraverseAction.DONE
                }
            }

            override fun endSlice(slice: Slice) {
                if (this.treeDepth == 0) {
                    lastTopLevelSlice = slice
                }

                --this.treeDepth
            }
        })

        println()
        println("App Startup summary for $startedName (${process.id}):")
        println("\tStart offset:              ${(startupStartTime - model.beginTimestamp).secondValueToMillisecondString()}")
        println("\tStartup period end point:  ${startupEndTime.signal}")
        println("\tTime to first slice:       ${(mainThread.slices.first().startTime - startupStartTime).secondValueToMillisecondString()}")
        println("\tStartup duration:          ${(startupEndTime.timestamp - startupStartTime).secondValueToMillisecondString()}")
        println("\tUnallocated time:          ${unallocatedTime.secondValueToMillisecondString()}")
        println("\tTop-level slice information:")
        topLevelSlices.toSortedMap(java.lang.String.CASE_INSENSITIVE_ORDER).forEach { sliceType, eventDurations ->
            println("\t\t$sliceType")
            println("\t\t\tEvent count:    ${eventDurations.count()}")
            println("\t\t\tTotal duration: ${eventDurations.sum().secondValueToMillisecondString()}")
        }
        println()
        println("\tAll slice information:")
        allSlices.toSortedMap(java.lang.String.CASE_INSENSITIVE_ORDER).forEach { sliceType, eventList ->
            println("\t\t$sliceType")
            println("\t\t\tEvent count:           ${eventList.count()}")

            var totalDuration = 0.0
            var totalDurationSelf = 0.0
            var numSliceDetails = 0

            eventList.forEach { (duration, durationSelf, details) ->
                totalDuration += duration
                totalDurationSelf += durationSelf
                if (details != null) ++numSliceDetails
            }

            println("\t\t\tTotal duration:        ${totalDuration.secondValueToMillisecondString()}")
            println("\t\t\tTotal duration (self): ${totalDurationSelf.secondValueToMillisecondString()}")

            if (numSliceDetails > 0) {
                println("\t\t\tEvent details:")
                eventList.forEach { (duration, _, details) ->
                    if (details != null) println("\t\t\t\t$details @ ${duration.secondValueToMillisecondString()}")
                }
            }
        }
        println()
    }
}

/*
 * Main Function
 */

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: StartAnalyzerKt <trace filename>")
        return
    }

    val filename = args[0]

    println("Opening `$filename`")
    val trace = parseTrace(File(filename))
    measureStartup(trace)
}