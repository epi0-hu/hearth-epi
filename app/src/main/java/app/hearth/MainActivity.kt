package app.hearth

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import app.hearth.ui.CharacterEditScreen
import app.hearth.ui.CharacterListScreen
import app.hearth.ui.ChatScreen
import app.hearth.ui.GroupEditScreen
import app.hearth.ui.HearthBackground
import app.hearth.ui.HearthTheme
import app.hearth.ui.LocalReducedMotion
import app.hearth.ui.LorebookEditScreen
import app.hearth.ui.LorebookListScreen
import app.hearth.ui.RegexEditScreen
import app.hearth.ui.RegexListScreen
import app.hearth.ui.SettingsScreen
import app.hearth.ui.rememberSystemReducedMotion

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings
            HearthTheme(fontScale = settings.fontScale) {
                val reduced = rememberSystemReducedMotion() || !settings.ambientEffects
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    Box(Modifier.fillMaxSize()) {
                        // One persistent hearth behind every screen; it flares while a reply is being written.
                        val generating = vm.chat?.generating == true
                        LaunchedEffect(generating) { if (generating) askNotificationsOnce() }
                        HearthBackground(intensity = if (generating) 1f else 0f, animate = !reduced)
                        Box(Modifier.fillMaxSize().graphicsLayer()) { AppRoot(vm, reduced) }
                    }
                }
            }
        }
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onStart() {
        super.onStart()
        vm.appVisible = true
        Notifications.clearReply(this)
    }

    override fun onStop() {
        super.onStop()
        vm.appVisible = false
        vm.chat?.flush()
    }

    /** Asked once, the first time a reply is generated (Android 13+). */
    fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("hearth", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotif", false)) return
        prefs.edit().putBoolean("askedNotif", true).apply()
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun AppRoot(vm: MainViewModel, reduced: Boolean) {
    val holder = rememberSaveableStateHolder()
    val screen = vm.backStack.last()
    val depth = vm.backStack.size
    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    AnimatedContent(
        targetState = screen to depth,
        contentKey = { it.first.toString() },
        transitionSpec = {
            if (reduced) {
                ContentTransform(EnterTransition.None, ExitTransition.None)
            } else if (targetState.second >= initialState.second) {
                // Forward: new screen glides in from the right over a receding one.
                (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(260)))
                    .togetherWith(scaleOut(tween(320), targetScale = 0.94f) + fadeOut(tween(200)))
            } else {
                // Back: the previous screen rises back while the top one slides away.
                (scaleIn(tween(320), initialScale = 0.94f) + fadeIn(tween(260)))
                    .togetherWith(slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(tween(200)))
            }
        },
        label = "screens",
    ) { (s, _) ->
        holder.SaveableStateProvider(s.toString()) {
            when (s) {
                Screen.Characters -> CharacterListScreen(vm)
                is Screen.Chat -> ChatScreen(vm)
                is Screen.EditCharacter -> CharacterEditScreen(vm, s.charId)
                is Screen.EditGroup -> GroupEditScreen(vm, s.groupId)
                Screen.Regex -> RegexListScreen(vm)
                is Screen.EditRegex -> RegexEditScreen(vm, s.id)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Lorebooks -> LorebookListScreen(vm)
                is Screen.EditLorebook -> LorebookEditScreen(vm, s.id)
            }
        }
    }
}
