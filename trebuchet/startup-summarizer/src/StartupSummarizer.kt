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
 * TODO (chriswailes): Build a unified error reporting mechanism.
 * TODO (chriswailes): Support CSV output
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
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
 * Constants
 */

val FILENAME_PATTERN = Regex("launch-(\\w+)-(\\w+)-(quicken|speed|speed-profile)-(cold|warm)-\\d")

val INTERESTING_SLICES = arrayOf(
    SLICE_NAME_ACTIVITY_START,
    SLICE_NAME_ACTIVITY_THREAD_MAIN,
    SLICE_NAME_APP_IMAGE_INTERN_STRING,
    SLICE_NAME_APP_IMAGE_LOADING,
    SLICE_NAME_BIND_APPLICATION,
    SLICE_NAME_INFLATE,
    SLICE_NAME_POST_FORK,
    SLICE_NAME_ZYGOTE_INIT)

const val SAMPLE_THRESHOLD_APPLICATION = 5
const val SAMPLE_THRESHOLD_COMPILER = 5
const val SAMPLE_THRESHOLD_TEMPERATURE = 5

/*
 * Class Definition
 */

enum class CompilerFilter {
    QUICKEN,
    SPEED,
    SPEED_PROFILE
}

enum class Temperature {
    COLD,
    WARM
}

data class StartupRecord(val appName : String,
                         val startupTime : Double,
                         val timeToFirstSlice : Double,
                         val topLevelTimings : MutableMap<String, Double> = mutableMapOf(),
                         val nonNestedTimings : MutableMap<String, Double> = mutableMapOf(),
                         val undifferentiatedTimings : MutableMap<String, Double> = mutableMapOf())

class CompilerRecord(val cold : MutableList<StartupRecord> = mutableListOf(),
                     val warm : MutableList<StartupRecord> = mutableListOf()) {

    fun numSamples() : Int {
        return this.cold.size + this.warm.size
    }
}

class ApplicationRecord(val quicken : CompilerRecord = CompilerRecord(),
                        val speed : CompilerRecord = CompilerRecord(),
                        val speedProfile : CompilerRecord = CompilerRecord()) {

    fun numSamples() : Int {
        return this.quicken.numSamples() + this.speed.numSamples() + this.speedProfile.numSamples()
    }
}

class SystemServerException: Exception("Unable to locate system server")

/*
 * Class Extensions
 */

/*
 * Helper Functions
 */

fun addStartupRecord(records : MutableMap<String, ApplicationRecord>, record : StartupRecord, appName : String, compiler : CompilerFilter, temperature : Temperature) {
    val applicationRecord = records.getOrPut(appName) { ApplicationRecord() }

    val compilerRecord =
        when (compiler) {
            CompilerFilter.QUICKEN -> applicationRecord.quicken
            CompilerFilter.SPEED -> applicationRecord.speed
            CompilerFilter.SPEED_PROFILE -> applicationRecord.speedProfile
        }

    when (temperature) {
        Temperature.COLD -> compilerRecord.cold
        Temperature.WARM -> compilerRecord.warm
    }.add(record)
}

fun averageAndStandardDeviation(values : List<Double>) : Pair<Double, Double> {
    val total = values.sum()
    val averageValue = total / values.size

    var sumOfSquaredDiffs = 0.0

    values.forEach { value ->
        sumOfSquaredDiffs += (value - averageValue).pow(2)
    }

    return Pair(averageValue, sqrt(sumOfSquaredDiffs / values.size))
}

fun extractStartupRecords(model : Model) : List<StartupRecord> {
    val records : MutableList<StartupRecord> = mutableListOf()

    // TODO: Use unified reporting mechanism when it is implemented.
    val systemServerIDs = model.findIDsByName(PROC_NAME_SYSTEM_SERVER) ?: throw SystemServerException()

    val systemServerProc = model.processes[systemServerIDs.first]
    val startedApps: MutableMap<Int, Pair<String, Double>> = mutableMapOf()

    systemServerProc!!.threads.forEach { thread ->
        SliceQueries.iterSlices(thread) { slice ->
            if (slice.name.startsWith(SLICE_NAME_PROC_START)) {

                val newProcName = slice.name.split(':', limit = 2)[1].trim()
                val newProcIDs = model.findIDsByName(newProcName)

                if (newProcIDs != null && !startedApps.containsKey(newProcIDs.first)) {
                    startedApps[newProcIDs.first] = Pair(newProcName, slice.startTime)
                }

                // TODO: Use unified reporting mechanism when it is implemented.
//                when {
//                    newProcIDs == null -> println("\tUnable to find PID for process `$newProcName`")
//                    startedApps.containsKey(newProcIDs.first) -> println("\tPID already mapped to started app")
//                    else -> startedApps[newProcIDs.first] = Pair(newProcName, slice.startTime)
//                }
            }
        }
    }

    startedApps.forEach { pid, (startedName, startupStartTime) ->
        val process = model.processes[pid]
        val mainThread = process!!.threads[0]

        val startupEndTime = model.getStartupEndTime(mainThread, startupStartTime)

        // Processes with end points defined by the default duration are currently noise.
        if (startupEndTime.signal != "DefaultDuration") {

            val newRecord = StartupRecord(startedName,
                startupEndTime.timestamp - startupStartTime,
                mainThread.slices.first().startTime - startupStartTime)

            SliceQueries.traverseSlices(mainThread, object : SliceTraverser {
                // Our depth down an individual tree in the slice forest.
                var treeDepth = -1

                val sliceDepths: MutableMap<String, Int> = mutableMapOf()

                override fun beginSlice(slice: Slice): TraverseAction {
                    val sliceInfo = parseSliceInfo(slice.name)

                    ++this.treeDepth

                    val sliceDepth = this.sliceDepths.getOrDefault(sliceInfo.type, -1)
                    this.sliceDepths[sliceInfo.type] = sliceDepth + 1

                    if (slice.startTime < startupEndTime.timestamp) {
                        // This slice starts during the startup period.  If it
                        // ends within the startup period we will record info
                        // from this slice.  Either way we will visit its
                        // children.

                        if (slice.endTime <= startupEndTime.timestamp) {
                            // Top-level timing
                            if (this.treeDepth == 0) {
                                val oldValue = newRecord.topLevelTimings.getOrDefault(sliceInfo.type, 0.0)
                                newRecord.topLevelTimings[sliceInfo.type] = oldValue + slice.duration
                            }

                            if (INTERESTING_SLICES.contains(sliceInfo.type)) {
                                // Undifferentiated timing
                                var oldValue = newRecord.undifferentiatedTimings.getOrDefault(sliceInfo.type, 0.0)
                                newRecord.undifferentiatedTimings[sliceInfo.type] = oldValue + slice.durationSelf

                                // Non-nested timing
                                if (sliceDepths[sliceInfo.type] == 0) {
                                    oldValue = newRecord.nonNestedTimings.getOrDefault(sliceInfo.type, 0.0)
                                    newRecord.nonNestedTimings[sliceInfo.type] = oldValue + slice.duration
                                }
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

                override fun endSlice(slice: Slice) {
                    val sliceInfo = parseSliceInfo(slice.name)

                    --this.treeDepth
                    this.sliceDepths[sliceInfo.type] = this.sliceDepths[sliceInfo.type]!! - 1
                }
            })

            records.add(newRecord)
        }
    }

    return records
}

fun parseFileName(fileName : String) : Triple<String, CompilerFilter, Temperature> {
    val md = FILENAME_PATTERN.find(fileName)

    if (md != null) {
        val compilerFilter =
            when (md.groups[3]!!.value) {
                "quicken" -> CompilerFilter.QUICKEN
                "speed" -> CompilerFilter.SPEED
                "speed-profile" -> CompilerFilter.SPEED_PROFILE
                else -> throw Exception("Bad compiler match data.")
            }

        val temperature =
            when (md.groups[4]!!.value) {
                "cold" -> Temperature.COLD
                "warm" -> Temperature.WARM
                else -> throw Exception("Bad temperature match data.")
            }

        return Triple(md.groups[1]!!.value, compilerFilter, temperature)
    } else {
        println("Unrecognized file name: $fileName")
        exitProcess(1)
    }
}

fun printAppRecord(record : ApplicationRecord) {

    if (record.quicken.numSamples() > SAMPLE_THRESHOLD_COMPILER) {
        println("\tQuicken:")
        printCompilerRecord(record.quicken)
    }

    if (record.speed.numSamples() > SAMPLE_THRESHOLD_COMPILER) {
        println("\tSpeed:")
        printCompilerRecord(record.speed)
    }

    if (record.speedProfile.numSamples() > SAMPLE_THRESHOLD_COMPILER) {
        println("\tSpeed Profile:")
        printCompilerRecord(record.speedProfile)
    }
}

fun printCompilerRecord(record : CompilerRecord) {

    if (record.cold.size > SAMPLE_THRESHOLD_TEMPERATURE) {
        println("\t\tCold:")
        printSampleSet(record.cold)
    }

    if (record.warm.size > SAMPLE_THRESHOLD_TEMPERATURE) {
        println("\t\tWarm:")
        printSampleSet(record.warm)
    }
}

fun printSampleSet(records : List<StartupRecord>) {

    val (startupTimeAverage, startupTimeStandardDeviation) = averageAndStandardDeviation(records.map {it.startupTime})
    val (timeToFirstSliceAverage, timeToFirstSliceStandardDeviation) = averageAndStandardDeviation(records.map {it.timeToFirstSlice})

    println("\t\t\tAverage startup time:            ${startupTimeAverage.secondValueToMillisecondString()}")
    println("\t\t\tStartup time standard deviation: ${startupTimeStandardDeviation.secondValueToMillisecondString()}")
    println("\t\t\tAverage time to first slice:     ${timeToFirstSliceAverage.secondValueToMillisecondString()}")
    println("\t\t\tTTFS standard deviation:         ${timeToFirstSliceStandardDeviation.secondValueToMillisecondString()}")
    println()

    // Definition of printing helper.
    fun printTimings(timingMaps : List<Map<String, Double>>) {
        val samples : MutableMap<String, MutableList<Double>> = mutableMapOf()

        timingMaps.forEach { timingMap ->
            timingMap.forEach {sliceType, duration ->
                samples.getOrPut(sliceType) { mutableListOf() }.add(duration)
            }
        }

        samples.forEach {sliceType, sliceSamples ->
            val (sliceDurationAverage, sliceDurationStandardDeviation) = averageAndStandardDeviation(sliceSamples)

            println("\t\t\t\t$sliceType:")

            println("\t\t\t\t\tAverage duration:     ${sliceDurationAverage.secondValueToMillisecondString()}")
            println("\t\t\t\t\tStandard deviation:   ${sliceDurationStandardDeviation.secondValueToMillisecondString()}")
            println("\t\t\t\t\tStartup time percent: %.2f%%".format((sliceDurationAverage / startupTimeAverage) * 100))
        }
    }

    /*
     * Timing accumulation
     */

    println("\t\t\tTop-level timings:")
    printTimings(records.map {it.topLevelTimings})
    println()
    println("\t\t\tNon-nested timings:")
    printTimings(records.map {it.nonNestedTimings})
    println()
    println("\t\t\tUndifferentiated timing:")
    printTimings(records.map {it.undifferentiatedTimings})
    println()
}

/*
 * Main Function
 */

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: StartupSummarizer <trace files>")
        return
    }

    val records : MutableMap<String, ApplicationRecord> = mutableMapOf()

    var processedFiles = 0
    var erroredFiles = 0

    args.forEach { fileName ->
        val (_, compiler, temperature) = parseFileName(fileName)

        val trace = parseTrace(File(fileName), false)

        try {
            val traceRecords : List<StartupRecord> = extractStartupRecords(trace)

            ++processedFiles

            print("\rProgress: $processedFiles ($erroredFiles) / ${args.size}")

            traceRecords.forEach { startupRecord ->
                addStartupRecord(records, startupRecord, startupRecord.appName, compiler, temperature)
            }

        } catch (e: SystemServerException) {
            ++processedFiles
            ++erroredFiles
            print("\rProgress: $processedFiles ($erroredFiles) / ${args.size}")
        }
    }

    println()

    records.forEach { appName, record ->
        if (record.numSamples() > SAMPLE_THRESHOLD_APPLICATION) {
            println("$appName:")
            printAppRecord(record)
            println()
        }
    }
}