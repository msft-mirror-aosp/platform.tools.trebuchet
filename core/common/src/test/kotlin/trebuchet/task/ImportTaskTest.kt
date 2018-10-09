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

package trebuchet.task

import org.junit.Assert.*
import org.junit.Test
import trebuchet.extras.findSampleData
import trebuchet.importers.DummyImportFeedback
import trebuchet.io.BufferProducer
import trebuchet.io.DataSlice
import trebuchet.io.asSlice
import trebuchet.model.Model
import trebuchet.queries.SliceQueries
import java.io.File

class ImportTaskTest {
    @Test fun testImportCameraTrace() {
        val model = import("hdr-0608-4-trace.html")
        val slices = SliceQueries.selectAll(model) { it.name.startsWith("MergeShot")}
        assertEquals(2, slices.size)
        assertEquals(0.868, slices[0].duration, .001)
        assertEquals(0.866, slices[1].duration, .001)
    }

    @Test fun testImportCalTrace1() {
        val model = import("caltrace1.html")
        val counterName = "com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity#1"
        val process = model.processes.values.find { it.name == "surfaceflinger" }!!
        val thread = process.threads.find { it.name == "surfaceflinger" }!!
        val slices = SliceQueries.selectAll(thread) { it.name == "handleMessageRefresh" }
        assertEquals(103, slices.size)
        assertFalse(slices.any { it.duration <= 0.0 })
        val totalDuration = slices.map { it.duration }.reduce { a,b -> a+b }
        assertEquals(.217984, totalDuration, .00001)
        val counter = process.counters.find { it.name == counterName }
        assertNotNull(counter)
        counter!!
        assertEquals(2, counter.events.filter { it.count == 2 }.size)
        assertFalse(counter.events.any { it.count < 0 || it.count > 2})
    }

    @Test fun testImportSample() {
        val model = import("sample.ftrace")
        val process = model.processes[6381]!!
        val thread = process.threads.find { it.name == "RenderThread" }!!
        assertEquals(6506, thread.id)
    }

    private fun import(filename: String): Model {
        val task = ImportTask(DummyImportFeedback)
        val file = File(findSampleData(), filename)
        assertTrue(file.exists())
        val model = task.import(readFile(file))
        assertFalse(model.isEmpty())
        return model
    }

    fun readFile(file: File): BufferProducer {
        val inputStream = file.inputStream()
        return object : BufferProducer {
            override fun next(): DataSlice? {
                val buffer = ByteArray(2 * 1024 * 1024)
                val read = inputStream.read(buffer)
                if (read == -1) return null
                return buffer.asSlice(read)
            }
        }
    }
}