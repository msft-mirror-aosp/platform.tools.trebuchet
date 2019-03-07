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
 * TODO (chriswailes): Add an argument parser
 * TODO (chriswailes): Move slice name parsing into slice class (if approved by jreck)
 */

/*
 * Imports
 */

import trebuchet.model.Model
import trebuchet.model.ProcessModel
import trebuchet.model.SchedulingState
import trebuchet.model.base.Slice
import trebuchet.queries.SliceQueries
import trebuchet.queries.SliceTraverser
import trebuchet.queries.TraverseAction

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
const val SLICE_NAME_ACTIVITY_START = "activityStart"
const val SLICE_NAME_ACTIVITY_THREAD_MAIN = "ActivityThreadMain"
const val SLICE_NAME_APP_IMAGE_INTERN_STRING = "AppImage:InternString"
const val SLICE_NAME_APP_IMAGE_LOADING = "AppImage:Loading"
const val SLICE_NAME_APP_LAUNCH = "launching"
const val SLICE_NAME_ALTERNATE_DEX_OPEN_START = "Dex file open"
const val SLICE_NAME_BIND_APPLICATION = "bindApplication"
const val SLICE_NAME_DRAWING = "drawing"
const val SLICE_NAME_INFLATE = "inflate"
const val SLICE_NAME_OPEN_DEX_FILE_FUNCTION = "std::unique_ptr<const DexFile> art::OatDexFile::OpenDexFile(std::string *) const"
const val SLICE_NAME_OBFUSCATED_TRACE_START = "X."
const val SLICE_NAME_POST_FORK = "PostFork"
const val SLICE_NAME_PROC_START = "Start proc"
const val SLICE_NAME_REPORT_FULLY_DRAWN = "ActivityManager:ReportingFullyDrawn"
const val SLICE_NAME_ZYGOTE_INIT = "ZygoteInit"

val SLICE_MAPPERS = arrayOf(
    Regex("^(Collision check)"),
    Regex("^(launching):\\s+([\\w\\.])+"),
    Regex("^(Lock contention).*"),
    Regex("^(monitor contention).*"),
    Regex("^(NetworkSecurityConfigProvider.install)"),
    Regex("^(notifyContentCapture\\((?:true|false)\\))\\sfor\\s(.*)"),
    Regex("^(Open dex file)(?: from RAM)? ([\\w\\./]*)"),
    Regex("^(Open oat file)\\s+(.*)"),
    Regex("^(RegisterDexFile)\\s+(.*)"),
    Regex("^($SLICE_NAME_REPORT_FULLY_DRAWN)\\s+(.*)"),
    Regex("^(serviceCreate):.*className=([\\w\\.]+)"),
    Regex("^(serviceStart):.*cmp=([\\w\\./]+)"),
    Regex("^(Setup proxies)"),
    Regex("^($SLICE_NAME_PROC_START):\\s+(.*)"),
    Regex("^(SuspendThreadByThreadId) suspended (.+)$"),
    Regex("^(VerifyClass)(.*)"),

    // Default pattern for slices with a single-word name.
    Regex("^([\\w:]+)$")
)

/*
 * Class Definition
 */

data class SliceContents(val name: String, val value: String?)
data class StartupEvent(val proc : ProcessModel,
                        val name : String,
                        val startTime : Double,
                        val endTime : Double,
                        val serverSideForkTime : Double,
                        val reportFullyDrawnTime : Double?,
                        val firstSliceTime : Double,
                        val undifferentiatedTime : Double,
                        val schedTimings : Map<SchedulingState, Double>,
                        val allSlicesInfo : AggregateSliceInfoMap,
                        val topLevelSliceInfo : AggregateSliceInfoMap,
                        val undifferentiatedSliceInfo : AggregateSliceInfoMap,
                        val nonNestedSliceInfo : AggregateSliceInfoMap)

class AggregateSliceInfo {
    var count : Int = 0
    var totalTime : Double = 0.0

    val values : MutableMap<String, Pair<Int, Double>> = mutableMapOf()
}

typealias MutableAggregateSliceInfoMap = MutableMap<String, AggregateSliceInfo>
typealias AggregateSliceInfoMap = Map<String, AggregateSliceInfo>

class MissingProcessInfoException(pid : Int) : Exception("Missing process info for PID $pid")

class MissingProcessException : Exception {
    constructor(name : String) {
        Exception("Unable to find process: $name")
    }

    constructor(name : String, lowerBound : Double, upperBound : Double) {
        Exception("Unable to find process: $name" +
                  " (Bounds: [${lowerBound.secondValueToMillisecondString()}," +
                  " ${upperBound.secondValueToMillisecondString()}]")
    }
}

class MissingSliceException : Exception {
    constructor(pid : Int, type : String, value : String?) {
        Exception("Unable to find slice. PID = $pid, Type = $type" +
                  (if (value == null) "" else ", Value = $value"))
    }

    constructor(pid : Int, type : String, value : String?, lowerBound : Double, upperBound : Double) {
        Exception("Unable to find slice. PID = $pid, Type = $type" +
                  (if (value == null) "" else ", Value = $value") +
                  " (Bounds: [${lowerBound.secondValueToMillisecondString()}," +
                  " ${upperBound.secondValueToMillisecondString()}])")
    }

    constructor (pid : Int, pattern : Regex) {
        Exception("Unable to find slice.  PID = $pid, Pattern = $pattern")
    }

    constructor (pid : Int, pattern : Regex, lowerBound : Double, upperBound : Double) {
        Exception("Unable to find slice.  PID = $pid, Pattern = $pattern" +
                  " (Bounds: [${lowerBound.secondValueToMillisecondString()}," +
                  " ${upperBound.secondValueToMillisecondString()}])")
    }
}

/*
 * Class Extensions
 */

fun Double.secondValueToMillisecondString() = "%.3f ms".format(this * 1000.0)

fun ProcessModel.fuzzyNameMatch(queryName : String) : Boolean {
    if (queryName.endsWith(this.name)) {
        return true
    } else {
        for (thread in this.threads) {
            if (queryName.endsWith(thread.name)) {
                return true
            }
        }
    }

    return false
}

fun Model.findProcess(queryName: String,
                      lowerBound : Double = this.beginTimestamp,
                      upperBound : Double = this.endTimestamp): ProcessModel {

    for (process in this.processes.values) {
        if (process.fuzzyNameMatch(queryName)) {
            val firstSliceStart =
                process.
                    threads.
                    map { it.slices }.
                    filter { it.isNotEmpty() }.
                    map { it.first().startTime }.
                    min() ?: throw MissingProcessInfoException(process.id)

            if (firstSliceStart in lowerBound..upperBound) {
                return process
            }
        }
    }

    if (lowerBound == this.beginTimestamp && upperBound == this.endTimestamp) {
        throw MissingProcessException(queryName)
    } else {
        throw MissingProcessException(queryName, lowerBound, upperBound)
    }
}

fun Model.getStartupEvents() : List<StartupEvent> {
    val systemServerProc = this.findProcess(PROC_NAME_SYSTEM_SERVER)

    val startupEvents = mutableListOf<StartupEvent>()

    systemServerProc.asyncSlices.forEach { systemServerSlice ->
        if (systemServerSlice.name.startsWith(SLICE_NAME_APP_LAUNCH)) {

            val newProcName    = systemServerSlice.name.split(':', limit = 2)[1].trim()
            val newProc        = this.findProcess(newProcName, systemServerSlice.startTime, systemServerSlice.endTime)
            val startProcSlice = systemServerProc.findFirstSlice(SLICE_NAME_PROC_START, newProcName, systemServerSlice.startTime, systemServerSlice.endTime)
            val rfdSlice       = systemServerProc.findFirstSliceOrNull(SLICE_NAME_REPORT_FULLY_DRAWN, newProcName, systemServerSlice.startTime)
            val firstSliceTime = newProc.threads.map { it.slices.firstOrNull()?.startTime ?: Double.POSITIVE_INFINITY }.min()!!

            val schedSliceInfo : MutableMap<SchedulingState, Double> = mutableMapOf()
            newProc.threads.first().schedSlices.forEach schedLoop@ { schedSlice ->
                val origVal = schedSliceInfo.getOrDefault(schedSlice.state, 0.0)

                when {
                    schedSlice.startTime >= systemServerSlice.endTime -> return@schedLoop
                    schedSlice.endTime <= systemServerSlice.endTime   -> schedSliceInfo[schedSlice.state] = origVal + schedSlice.duration
                    else                                              -> {
                        schedSliceInfo[schedSlice.state] = origVal + (systemServerSlice.endTime - schedSlice.startTime)
                        return@schedLoop
                    }
                }
            }

            var undifferentiatedTime = 0.0

            val allSlicesInfo : MutableAggregateSliceInfoMap = mutableMapOf()
            val topLevelSliceInfo : MutableAggregateSliceInfoMap = mutableMapOf()
            val undifferentiatedSliceInfo : MutableAggregateSliceInfoMap = mutableMapOf()
            val nonNestedSliceInfo : MutableAggregateSliceInfoMap = mutableMapOf()

            SliceQueries.traverseSlices(newProc.threads.first(), object : SliceTraverser {
                // Our depth down an individual tree in the slice forest.
                var treeDepth = -1
                val sliceDepths: MutableMap<String, Int> = mutableMapOf()

                var lastTopLevelSlice : Slice? = null

                override fun beginSlice(slice : Slice) : TraverseAction {
                    val sliceContents = parseSliceName(slice.name)

                    ++this.treeDepth

                    val sliceDepth = this.sliceDepths.getOrDefault(sliceContents.name, -1)
                    this.sliceDepths[sliceContents.name] = sliceDepth + 1

                    if (slice.startTime < systemServerSlice.endTime) {
                        // This slice starts during the startup period.  If it
                        // ends within the startup period we will record info
                        // from this slice.  Either way we will visit its
                        // children.

                        if (this.treeDepth == 0 && this.lastTopLevelSlice != null) {
                            undifferentiatedTime += (slice.startTime - this.lastTopLevelSlice!!.endTime)
                        }

                        if (slice.endTime <= systemServerSlice.endTime) {
                            // This slice belongs in our collection.

                            // All Slice Timings
                            aggregateSliceInfo(allSlicesInfo, sliceContents, slice.duration)

                            // Undifferentiated Timings
                            aggregateSliceInfo(undifferentiatedSliceInfo, sliceContents, slice.durationSelf)

                            // Top-level timings
                            if (this.treeDepth == 0) {
                                aggregateSliceInfo(topLevelSliceInfo, sliceContents, slice.duration)
                            }

                            // Non-nested timings
                            if (sliceDepths[sliceContents.name] == 0) {
                                aggregateSliceInfo(nonNestedSliceInfo, sliceContents, slice.duration)
                            }
                        }

                        return TraverseAction.VISIT_CHILDREN

                    } else {
                        // All contents of this slice occur after the startup
                        // period has ended. We don't need to record anything
                        // or traverse any children.
                        return TraverseAction.DONE
                    }
                }

                override fun endSlice(slice : Slice) {
                    if (this.treeDepth == 0) {
                        lastTopLevelSlice = slice
                    }

                    val sliceInfo = parseSliceName(slice.name)
                    this.sliceDepths[sliceInfo.name] = this.sliceDepths[sliceInfo.name]!! - 1

                    --this.treeDepth
                }
            })

            startupEvents.add(StartupEvent(newProc,
                                           newProcName,
                                           systemServerSlice.startTime,
                                           systemServerSlice.endTime,
                                           startProcSlice.duration,
                                           rfdSlice?.startTime,
                                           firstSliceTime,
                                           undifferentiatedTime,
                                           schedSliceInfo,
                                           allSlicesInfo,
                                           topLevelSliceInfo,
                                           undifferentiatedSliceInfo,
                                           nonNestedSliceInfo))
        }
    }

    return startupEvents
}

fun aggregateSliceInfo(infoMap : MutableAggregateSliceInfoMap, sliceContents : SliceContents, duration : Double) {
    val aggInfo = infoMap.getOrPut(sliceContents.name, ::AggregateSliceInfo)
    ++aggInfo.count
    aggInfo.totalTime += duration
    if (sliceContents.value != null) {
        val (uniqueValueCount, uniqueValueDuration) = aggInfo.values.getOrDefault(sliceContents.value, Pair(0, 0.0))
        aggInfo.values[sliceContents.value] = Pair(uniqueValueCount + 1, uniqueValueDuration + duration)
    }
}

// TODO: Move into SliceQueries
fun ProcessModel.findFirstSlice(queryType : String,
                                queryValue : String?,
                                lowerBound : Double = this.model.beginTimestamp,
                                upperBound : Double = this.model.endTimestamp) : Slice {

    val foundSlice = this.findFirstSliceOrNull(queryType, queryValue, lowerBound, upperBound)

    if (foundSlice != null) {
        return foundSlice
    } else if (lowerBound == this.model.beginTimestamp && upperBound == this.model.endTimestamp) {
        throw MissingSliceException(this.id, queryType, queryValue)
    } else {
        throw MissingSliceException(this.id, queryType, queryValue, lowerBound, upperBound)
    }
}

// TODO: Move into SliceQueries
fun ProcessModel.findFirstSlice(pattern : Regex,
                                lowerBound : Double = this.model.beginTimestamp,
                                upperBound : Double = this.model.endTimestamp) : Slice {

    val foundSlice = this.findFirstSliceOrNull(pattern, lowerBound, upperBound)

    if (foundSlice != null) {
        return foundSlice
    } else if (lowerBound == this.model.beginTimestamp && upperBound == this.model.endTimestamp) {
        throw MissingSliceException(this.id, pattern)
    } else {
        throw MissingSliceException(this.id, pattern, lowerBound, upperBound)
    }
}

// TODO: Move into SliceQueries
fun ProcessModel.findFirstSliceOrNull(queryType : String,
                                queryValue : String?,
                                lowerBound : Double = this.model.beginTimestamp,
                                upperBound : Double = this.model.endTimestamp) : Slice? {

    return SliceQueries.selectFirst(this) { slice ->
        val sliceInfo = parseSliceName(slice.name)

        sliceInfo.name == queryType &&
            (queryValue == null || sliceInfo.value == queryValue) &&
        lowerBound <= slice.startTime &&
        slice.startTime <= upperBound
    }
}

// TODO: Move into SliceQueries
fun ProcessModel.findFirstSliceOrNull(pattern : Regex,
                                lowerBound : Double = this.model.beginTimestamp,
                                upperBound : Double = this.model.endTimestamp) : Slice? {

    return SliceQueries.selectFirst(this) { slice ->
        pattern.find(slice.name) != null &&
            lowerBound <= slice.startTime &&
            slice.startTime <= upperBound
    }
}

// TODO: Move into SliceQueries
fun ProcessModel.findAllSlices(queryType : String,
                               queryValue : String?,
                               lowerBound : Double = this.model.beginTimestamp,
                               upperBound : Double = this.model.endTimestamp) : List<Slice> {

    return SliceQueries.selectAll(this) { slice ->
        val sliceInfo = parseSliceName(slice.name)

        sliceInfo.name == queryType &&
                (queryValue == null || sliceInfo.value == queryValue) &&
        lowerBound <= slice.startTime &&
        slice.startTime <= upperBound
    }
}

// TODO: Move into SliceQueries
fun ProcessModel.findAllSlices(pattern : Regex,
                               lowerBound : Double = this.model.beginTimestamp,
                               upperBound : Double = this.model.endTimestamp) : List<Slice> {

    return SliceQueries.selectAll(this) { slice ->
        pattern.find(slice.name) != null &&
            lowerBound <= slice.startTime &&
            slice.startTime <= upperBound
    }
}

/*
 * Functions
 */

fun parseSliceName(sliceString: String): SliceContents {
    when {
    // Handle special cases.
        sliceString == SLICE_NAME_OPEN_DEX_FILE_FUNCTION -> return SliceContents("Open dex file function invocation", null)
        sliceString.startsWith(SLICE_NAME_ALTERNATE_DEX_OPEN_START) -> return SliceContents("Open dex file", sliceString.split(" ").last().trim())
        sliceString.startsWith(SLICE_NAME_OBFUSCATED_TRACE_START) -> return SliceContents("Obfuscated trace point", null)
        sliceString[0] == '/' -> return SliceContents("Load Dex files from classpath", null)

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

                    return SliceContents(sliceType, sliceDetails)
                }
            }

            return SliceContents("Unknown Slice", sliceString)
        }
    }

}
