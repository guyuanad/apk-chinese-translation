/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.proto.Skill
import dagger.hilt.android.qualifiers.ApplicationContext
import io.modelcontextprotocol.kotlin.sdk.client.Client
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

private const val TAG = "AGSkillService"

/**
 * Singleton service that manages skill data.
 * Replaces SkillManagerViewModel usage in AgentTools.
 */
@Singleton
class SkillService @Inject constructor(
  private val dataStoreRepository: DataStoreRepository,
  @ApplicationContext private val context: Context,
) {
  private val _skillsState = MutableStateFlow<List<Skill>>(emptyList())
  val skillsState = _skillsState.asStateFlow()
  private var skillsLoaded = false

  /** Loads skills from DataStore and asset files into memory. */
  suspend fun loadSkills() {
    if (skillsLoaded) return

    Log.d(TAG, "Loading skills...")
    val allSkills = dataStoreRepository.getAllSkills()
    val builtInSkills = allSkills.filter { it.builtIn }
    val customSkills = allSkills.filter { !it.builtIn }

    val builtInSelectionMap = builtInSkills.associate {
      it.name to Pair(it.selected, it.userModifiedSelection)
    }

    // Load built-in skills from assets
    val loadedBuiltInSkills = mutableListOf<Skill>()
    try {
      val skillAssetDirs = context.assets.list("skills") ?: emptyArray()
      for (dirName in skillAssetDirs) {
        val skillMdPath = "skills/$dirName/SKILL.md"
        try {
          context.assets.open(skillMdPath).use { inputStream ->
            val mdContent = inputStream.bufferedReader().use { it.readText() }
            // We need to parse the skill - use a simple parser inline
            val skillProto = parseSkillMd(mdContent, builtIn = true, importDirName = dirName)
            skillProto?.let { skill ->
              val defaultSelected = skill.name !in DEFAULT_DISABLED_SKILLS
              val (persistedSelected, userModified) =
                builtInSelectionMap[skill.name] ?: Pair(defaultSelected, false)
              val selectedState = if (userModified) persistedSelected else defaultSelected
              loadedBuiltInSkills.add(
                skill.toBuilder()
                  .setSelected(selectedState)
                  .setUserModifiedSelection(userModified)
                  .build()
              )
            }
          }
        } catch (e: Exception) {
          Log.w(TAG, "SKILL.md not found or error for asset skill $dirName", e)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error listing assets/skills", e)
    }

    // Combine built-in skills with custom skills
    val finalSkills = loadedBuiltInSkills.toMutableList()
    for (customSkill in customSkills) {
      if (finalSkills.none { it.name == customSkill.name }) {
        finalSkills.add(customSkill)
      }
    }

    // Update DataStore
    dataStoreRepository.setSkills(finalSkills)
    _skillsState.value = finalSkills
    skillsLoaded = true
    Log.d(TAG, "Loaded ${finalSkills.size} skills")
  }

  /** Gets the list of currently selected skills. */
  fun getSelectedSkills(): List<Skill> {
    return _skillsState.value.filter { it.selected }
  }

  /** Gets the JS skill URL for a given skill and script. */
  fun getJsSkillUrl(skillName: String, scriptName: String): String? {
    val skill = _skillsState.value.find { it.name == skillName } ?: return null
    var baseUrl = ""
    if (skill.importDirName.isNotEmpty()) {
      baseUrl = "$LOCAL_URL_BASE/${skill.importDirName}"
    } else if (skill.skillUrl.isNotEmpty()) {
      baseUrl = skill.skillUrl
    }
    if (baseUrl.isEmpty()) return null
    return "$baseUrl/scripts/$scriptName"
  }

  /** Gets the JS skill webview URL for a given skill. */
  fun getJsSkillWebviewUrl(skillName: String, url: String): String {
    val skill = _skillsState.value.find { it.name == skillName } ?: return url

    if (url.startsWith("http")) return url

    var baseUrl = ""
    if (skill.importDirName.isNotEmpty()) {
      baseUrl = "$LOCAL_URL_BASE/${skill.importDirName}"
    } else if (skill.skillUrl.isNotEmpty()) {
      baseUrl = skill.skillUrl
    }
    if (baseUrl.isEmpty()) return url
    return "$baseUrl/assets/$url"
  }

  /** Reads a secret for a given key. */
  fun readSecret(key: String): String? = dataStoreRepository.readSecret(key)

  /** Saves a secret for a given key. */
  fun saveSecret(key: String, value: String) = dataStoreRepository.saveSecret(key, value)

  /** Parses a SKILL.md file into a Skill proto. */
  private fun parseSkillMd(mdContent: String, builtIn: Boolean, importDirName: String = ""): Skill? {
    val parts = mdContent.split("---")
    if (parts.size < 3) return null

    val header = parts[1].trim()
    var name: String? = null
    var description: String? = null

    for (line in header.lines()) {
      val trimmed = line.trim()
      when {
        trimmed.startsWith("name:") -> name = trimmed.substringAfter("name:").trim()
        trimmed.startsWith("description:") -> description = trimmed.substringAfter("description:").trim()
      }
    }

    if (name.isNullOrEmpty() || description.isNullOrEmpty()) return null

    val instructions = parts.drop(2).joinToString("---").trim()

    return Skill.newBuilder()
      .setName(name)
      .setDescription(description)
      .setInstructions(instructions)
      .setBuiltIn(builtIn)
      .setSelected(true)
      .setImportDirName(importDirName)
      .build()
  }

  companion object {
    private val DEFAULT_DISABLED_SKILLS =
      setOf("calculate-hash", "kitchen-adventure", "text-spinner", "send-email")
  }
}
