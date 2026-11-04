package ru.secondbrain.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App-scoped lock state: lives in the container, not in the composition, so a rotation or theme change does
 * not ask for biometrics again. The background timeout in [Locked] still re-locks it.
 */
class LockState {
    var unlocked by mutableStateOf(false)
    var backgroundAt by mutableLongStateOf(0L)
}
