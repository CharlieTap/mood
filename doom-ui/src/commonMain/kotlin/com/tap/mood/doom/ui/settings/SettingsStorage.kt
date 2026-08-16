package com.tap.mood.doom.ui.settings

interface SettingsStorage {
    fun read(key: String): String?

    fun write(values: Map<String, String>)
}
