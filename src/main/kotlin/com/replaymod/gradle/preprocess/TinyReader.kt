/*
 * Copyright (c) 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.replaymod.gradle.preprocess

import net.fabricmc.mappings.Mappings
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingsReader
import java.io.IOException

class TinyReader(private val m: Mappings, private val from: String, private val to: String, private val appendNone: Boolean) : MappingsReader() {

    private fun procClassName(s: String): String {
        return if (appendNone) {
            if (s.indexOf('/') < 0) {
                "none/$s"
            } else {
                s
            }
        } else {
            s
        }
    }

    override fun read(mappings: MappingSet): MappingSet {
        for (entry in m.classEntries) {
            mappings.getOrCreateClassMapping(entry.get(from)).deobfuscatedName = procClassName(entry.get(to))
        }

        for (entry in m.fieldEntries) {
            val fromEntry = entry.get(from)
            val toEntry = entry.get(to)

            mappings.getOrCreateClassMapping(fromEntry.owner)
                    .getOrCreateFieldMapping(fromEntry.name, fromEntry.desc).deobfuscatedName = toEntry.name
        }

        for (entry in m.methodEntries) {
            val fromEntry = entry.get(from)
            val toEntry = entry.get(to)

            mappings.getOrCreateClassMapping(fromEntry.owner)
                    .getOrCreateMethodMapping(fromEntry.name, fromEntry.desc).deobfuscatedName = toEntry.name
        }

        return mappings
    }

    @Throws(IOException::class)
    override fun close() {

    }
}
