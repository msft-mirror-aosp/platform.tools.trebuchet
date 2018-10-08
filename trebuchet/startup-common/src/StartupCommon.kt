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
import trebuchet.model.ThreadModel
import trebuchet.model.base.Slice
import trebuchet.queries.SliceQueries
import trebuchet.queries.SliceTraverser
import trebuchet.queries.TraverseAction
import java.lang.Double.min

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
const val SLICE_NAME_ALTERNATE_DEX_OPEN_START = "Dex file open"
const val SLICE_NAME_BIND_APPLICATION = "bindApplication"
const val SLICE_NAME_INFLATE = "inflate"
const val SLICE_NAME_OPEN_DEX_FILE_FUNCTION = "std::unique_ptr<const DexFile> art::OatDexFile::OpenDexFile(std::string *) const"
const val SLICE_NAME_OBFUSCATED_TRACE_START = "X."
const val SLICE_NAME_POST_FORK = "PostFork"
const val SLICE_NAME_PROC_START = "Start proc"
const val SLICE_NAME_REPORT_FULLY_DRAWN = "reportFullyDrawn"
const val SLICE_NAME_ZYGOTE_INIT = "ZygoteInit"

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

fun Double.secondValueToMillisecondString() = "%.3f ms".format(this * 1000.0)

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
 * Functions
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