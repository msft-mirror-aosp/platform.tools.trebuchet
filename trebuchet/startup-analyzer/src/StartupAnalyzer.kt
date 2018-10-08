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

/*
 * Notes
 *
 * TODO (chriswailes): Support JSON output
 * TODO (chriswailes): Move slice name parsing into slice class (if approved by Trebuchet maintainer)
 */

/*
 * Imports
 */

import java.io.File
import trebuchet.model.Model
import trebuchet.extras.parseTrace
import trebuchet.model.ThreadModel
import trebuchet.model.base.Slice
import trebuchet.queries.SliceQueries
import trebuchet.queries.SliceTraverser
import trebuchet.queries.TraverseAction
import java.lang.Double.min
import kotlin.system.exitProcess

/*
 * Constants
 */

// Duration (in milliseconds) used for startup period when none of the
// appropriate events could be found.
const val DEFAULT_START_DURATION = 5000

const val PROC_NAME_SYSTEM_SERVER = "system_server"

const val SLICE_NAME_ACTIVITY_DESTROY = "activityDestroy"
const val SLICE_NAME_ACTIVITY_PAUSE = "activityPause"
const val SLICE_NAME_ACTIVITY_RESUME = "activityResume"
const val SLICE_NAME_ALTERNATE_DEX_OPEN_START = "Dex file open"
const val SLICE_NAME_OPEN_DEX_FILE_FUNCTION = "std::unique_ptr<const DexFile> art::OatDexFile::OpenDexFile(std::string *) const"
const val SLICE_NAME_OBFUSCATED_TRACE_START = "X."
const val SLICE_NAME_PROC_START = "Start proc"
const val SLICE_NAME_REPORT_FULLY_DRAWN = "reportFullyDrawn"

val SLICE_MAPPERS = arrayOf(
        Regex("^(Collision check)"),
        Regex("^(Lock contention).*"),
        Regex("^(monitor contention).*"),
        Regex("^(NetworkSecurityConfigProvider.install)"),
        Regex("^(Open dex file)(?: from RAM)? ([\\w\\./]*)"),
        Regex("^(Open oat file)\\s+(.*)"),
        Regex("^(RegisterDexFile)\\s+(.*)"),
        Regex("^(serviceCreate):.*className=([\\w\\.]+)"),
        Regex("^(serviceStart):.*cmp=([\\w\\./]+)"),
        Regex("^(Setup proxies)"),
        Regex("^(Start proc):\\s+(.*)"),
        Regex("^(SuspendThreadByThreadId) suspended (.+)$"),
        Regex("^(VerifyClass)(.*)"),

        // Default pattern for slices with a single-word name.
        Regex("^([\\w:]+)$")
)

/*
 * Class Definition
 */

data class SliceInfo(val type: String, val payload: String?)
data class StartupEndPoint(val timestamp: Double, val signal: String)

/*
 * Class Extensions
 */

private fun Double.secondValueToMillisecondString() = "%.3f ms".format(this * 1000.0)

private fun <K, V> MutableMap<K, MutableList<V>>.add(key: K, el: V) {
    this.getOrPut(key) { mutableListOf() }.add(el)
}

fun Model.findIDsByName(query_name: String): Pair<Int, Int?>? {
    for (process in this.processes.values) {
        if (query_name.endsWith(process.name)) {
            return Pair(process.id, null)

        } else {
            for (thread in process.threads) {
                if (query_name.endsWith(thread.name)) {
                    return Pair(process.id, thread.id)
                }
            }
        }
    }

    return null
}

// Find the time at which we've defined startup to have ended.
fun Model.getStartupEndTime(mainThread: ThreadModel, startupStartTime: Double): StartupEndPoint {
    var endTimeInfo: StartupEndPoint? = null

    SliceQueries.traverseSlices(mainThread, object : SliceTraverser {
        override fun beginSlice(slice: Slice): TraverseAction {
            if (endTimeInfo == null &&
                    (slice.name.startsWith(SLICE_NAME_ACTIVITY_DESTROY) ||
                            slice.name.startsWith(SLICE_NAME_ACTIVITY_PAUSE) ||
                            slice.name.startsWith(SLICE_NAME_ACTIVITY_RESUME))) {

                endTimeInfo = StartupEndPoint(slice.endTime, slice.name)

                return TraverseAction.VISIT_CHILDREN
            } else if (slice.name.startsWith(SLICE_NAME_REPORT_FULLY_DRAWN)) {
                endTimeInfo = StartupEndPoint(slice.endTime, slice.name)

                return TraverseAction.DONE
            } else {
                return TraverseAction.VISIT_CHILDREN
            }
        }
    })

    return endTimeInfo
            ?: StartupEndPoint(min(startupStartTime + DEFAULT_START_DURATION, this.endTimestamp), "DefaultDuration")
}

/*
 * Helper Functions
 */

fun parseSliceInfo(sliceString: String): SliceInfo {
    when {
        // Handle special cases.
        sliceString == SLICE_NAME_OPEN_DEX_FILE_FUNCTION -> return SliceInfo("Open dex file function invocation", null)
        sliceString.startsWith(SLICE_NAME_ALTERNATE_DEX_OPEN_START) -> return SliceInfo("Open dex file", sliceString.split(" ").last().trim())
        sliceString.startsWith(SLICE_NAME_OBFUSCATED_TRACE_START) -> return SliceInfo("Obfuscated trace point", null)
        sliceString[0] == '/' -> return SliceInfo("Load Dex files from classpath", null)

        else -> {
            // Search the slice mapping patterns.
            for (pattern in SLICE_MAPPERS) {
                val matchResult = pattern.find(sliceString)

                if (matchResult != null) {
                    val sliceType = matchResult.groups[1]!!.value.trim()

                    val sliceDetails =
                        if (matchResult.groups.size > 2 && !matchResult.groups[2]!!.value.isEmpty()) {
                            matchResult.groups[2]!!.value.trim()
                        } else {
                            null
                        }

                    return SliceInfo(sliceType, sliceDetails)
                }
            }

            return SliceInfo("Unknown Slice", sliceString)
        }
    }

}

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
                eventList.forEach { (duration, durationSelf, details) ->
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