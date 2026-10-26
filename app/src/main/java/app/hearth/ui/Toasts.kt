package app.hearth.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.hearth.MainViewModel
import kotlinx.coroutines.flow.collectLatest

/** Snackbar host fed by the view model's one-shot messages. */
@Composable
fun rememberToasts(vm: MainViewModel): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.toasts.collectLatest { host.showSnackbar(it) }
    }
    return host
}
