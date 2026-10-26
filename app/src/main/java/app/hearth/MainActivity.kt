package app.hearth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import app.hearth.ui.CharacterEditScreen
import app.hearth.ui.CharacterListScreen
import app.hearth.ui.ChatScreen
import app.hearth.ui.HearthTheme
import app.hearth.ui.LorebookEditScreen
import app.hearth.ui.LorebookListScreen
import app.hearth.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings
            HearthTheme(fontScale = settings.fontScale) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AppRoot(vm)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.chat?.flush()
    }
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val holder = rememberSaveableStateHolder()
    val screen = vm.backStack.last()
    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }
    holder.SaveableStateProvider(screen.toString()) {
        when (screen) {
            Screen.Characters -> CharacterListScreen(vm)
            is Screen.Chat -> ChatScreen(vm)
            is Screen.EditCharacter -> CharacterEditScreen(vm, screen.charId)
            Screen.Settings -> SettingsScreen(vm)
            Screen.Lorebooks -> LorebookListScreen(vm)
            is Screen.EditLorebook -> LorebookEditScreen(vm, screen.id)
        }
    }
}
