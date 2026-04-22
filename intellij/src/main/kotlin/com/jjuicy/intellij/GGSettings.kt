package com.jjuicy.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent application-level settings for jjuicy.
 *
 * Stored in `jjuicy.xml` in the IDE config directory.
 */
@State(
    name = "com.jjuicy.intellij.GGSettings",
    storages = [Storage("jjuicy.xml")]
)
class GGSettings : PersistentStateComponent<GGSettings.State> {

    data class State(
        // path to the gg/jjuicy binary; blank means auto-discover from PATH
        var binaryPath: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var binaryPath: String
        get() = state.binaryPath
        set(value) { state.binaryPath = value }

    companion object {
        val instance: GGSettings
            get() = ApplicationManager.getApplication().getService(GGSettings::class.java)
    }
}
