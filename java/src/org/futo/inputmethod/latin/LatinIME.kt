package org.futo.inputmethod.latin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodSubtype
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.accessibility.AccessibilityUtils
import org.futo.inputmethod.engine.ExpandableSuggestionBarConfiguration
import org.futo.inputmethod.engine.IMEManager
import org.futo.inputmethod.engine.general.WordLearner
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.BasicThemeProvider
import org.futo.inputmethod.latin.uix.DataStoreHelper
import org.futo.inputmethod.latin.uix.DynamicThemeProvider
import org.futo.inputmethod.latin.uix.DynamicThemeProviderOwner
import org.futo.inputmethod.latin.uix.EmojiTracker.useEmoji
import org.futo.inputmethod.latin.uix.HiddenKeysSetting
import org.futo.inputmethod.latin.uix.KeyBordersSetting
import org.futo.inputmethod.latin.uix.KeyHintsSetting
import org.futo.inputmethod.latin.uix.KeyboardColorScheme
import org.futo.inputmethod.latin.uix.SUGGESTION_BLACKLIST
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.THEME_KEY
import org.futo.inputmethod.latin.uix.UixManager
import org.futo.inputmethod.latin.uix.actions.CanThrowIfDebug
import org.futo.inputmethod.latin.uix.createInlineSuggestionsRequest
import org.futo.inputmethod.latin.uix.dataStore
import org.futo.inputmethod.latin.uix.differsFrom
import org.futo.inputmethod.latin.uix.forceUnlockDatastore
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.getSettingFlow
import org.futo.inputmethod.latin.uix.isDirectBootUnlocked
import org.futo.inputmethod.latin.uix.safeKeyboardPadding
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.theme.ThemeOption
import org.futo.inputmethod.latin.uix.theme.applyWindowColors
import org.futo.inputmethod.latin.uix.theme.getThemeOption
import org.futo.inputmethod.latin.uix.theme.orDefault
import org.futo.inputmethod.latin.uix.theme.presets.DefaultDarkScheme
import org.futo.inputmethod.latin.utils.JniUtils
import org.futo.inputmethod.updates.scheduleUpdateCheckingJob
import org.futo.inputmethod.v2keyboard.ComputedKeyboardSize
import org.futo.inputmethod.v2keyboard.FloatingKeyboardSize
import org.futo.inputmethod.v2keyboard.KeyboardSettings
import org.futo.inputmethod.v2keyboard.KeyboardSizeSettingKind
import org.futo.inputmethod.v2keyboard.KeyboardSizeStateProvider
import org.futo.inputmethod.v2keyboard.KeyboardSizingCalculator
import org.futo.inputmethod.v2keyboard.LayoutManager
import org.futo.inputmethod.v2keyboard.dimensionsSameAs
import org.futo.inputmethod.v2keyboard.getPrimaryLayoutOverride
import org.futo.inputmethod.v2keyboard.isFoldableInnerDisplayAllowed
import kotlin.math.roundToInt

/** Whether or not we can render into the navbar */
val SupportsNavbarExtension = Build.VERSION.SDK_INT >= 28

val SupportsNonComposing = Build.VERSION.SDK_INT >= 31

val UseTransparentNavbar =
    // https://github.com/futo-org/android-keyboard/issues/772
    !Build.MANUFACTURER.lowercase().contains("motorola")

val HideKeyboardWhenHardKeyboardConnected = SettingsKey(
    booleanPreferencesKey("hideKeyboardWhenHardKeyboardConnected"),
    false
)

private class UnlockedBroadcastReceiver(val onDeviceUnlocked: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
            onDeviceUnlocked()
        }
    }
}

open class InputMethodServiceCompose : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private lateinit var mLifecycleRegistry: LifecycleRegistry
    private lateinit var mViewModelStore: ViewModelStore
    private lateinit var mSavedStateRegistryController: SavedStateRegistryController

    fun setOwners() {
        val decorView = window.window?.decorView
        decorView?.setViewTreeLifecycleOwner(this)
        decorView?.setViewTreeViewModelStoreOwner(this)
        decorView?.setViewTreeSavedStateRegistryOwner(this)
    }

    override fun onCreate() {
        super.onCreate()

        mLifecycleRegistry = LifecycleRegistry(this)
        mLifecycleRegistry.currentState = Lifecycle.State.INITIALIZED

        mViewModelStore = ViewModelStore()

        mSavedStateRegistryController = SavedStateRegistryController.create(this)
        mSavedStateRegistryController.performRestore(null)

        mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onDestroy() {
        super.onDestroy()
        mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override val lifecycle: Lifecycle
        get() = mLifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = mSavedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore
        get() = mViewModelStore

    internal var composeView: ComposeView? = null

    override fun onCreateInputView(): View =
        ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setParentCompositionContext(null)

            setOwners()

            composeView = this
        }
}

class LatinIME : InputMethodServiceCompose(), LatinIMELegacy.SuggestionStripController,
        DynamicThemeProviderOwner, FoldStateProvider, KeyboardSizeStateProvider {

    val imeManager = IMEManager(this)

    val latinIMELegacy = LatinIMELegacy(
        this as InputMethodService,
        this as LatinIMELegacy.SuggestionStripController,
    )

    val uixManager = UixManager(this)

    val sizingCalculator = KeyboardSizingCalculator(this, uixManager)

    private var activeThemeOption: ThemeOption? = null
    private val activeColorScheme = mutableStateOf(DefaultDarkScheme.obtainColors(this))
    private var pendingRecreateKeyboard: Boolean = false

    val colorScheme get() = activeColorScheme.value
    val keyboardColor get() = colorScheme.keyboardSurface.let { fallback ->
        drawableProvider?.keyboardColor?.let { androidx.compose.ui.graphics.Color(it) } ?: fallback
    }

    val size: MutableState<ComputedKeyboardSize?> = mutableStateOf(null)
    private fun calculateSize(): ComputedKeyboardSize? = sizingCalculator.calculate(
        getPrimaryLayoutOverride(currentInputEditorInfo) ?: latinIMELegacy.mKeyboardSwitcher.keyboard?.mId?.mKeyboardLayoutSetName ?: "qwerty",
        Settings.getInstance().current
    )

    private var drawableProvider: DynamicThemeProvider? = null

    private var settingsRefreshRequired = false
    private fun recreateKeyboard() {
        latinIMELegacy.updateTheme()

        if(settingsRefreshRequired) {
            latinIMELegacy.mKeyboardSwitcher.loadKeyboard(
                currentInputEditorInfo ?: return,
                latinIMELegacy.mSettings.current,
                latinIMELegacy.currentAutoCapsState
            )
        } else {
            latinIMELegacy.mKeyboardSwitcher.mState.onLoadKeyboard(
                currentInputEditorInfo ?: return,
                latinIMELegacy.currentAutoCapsState,
                latinIMELegacy.mKeyboardSwitcher.keyboard?.mId?.mKeyboardLayoutSetName,
                null
            )
        }

        settingsRefreshRequired = false
    }

    private var isNavigationBarVisible = false
    fun updateNavigationBarVisibility(visible: Boolean? = null) {
        if(visible != null) isNavigationBarVisible = visible

        if(SupportsNavbarExtension) {
            val isFloating = size.value is FloatingKeyboardSize

            val color = (colorScheme.navigationBarColor ?: colorScheme.keyboardSurface)

            val colorToUse = when {
                isFloating -> Color.BLACK
                else -> (colorScheme.navigationBarColorForTransparency ?: color).toArgb()
            } and 0x00FFFFFF

            window.window?.let { window ->
                if(UseTransparentNavbar) {
                    applyWindowColors(window, colorToUse, statusBar = false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.setNavigationBarContrastEnforced(isFloating)
                    }

                    WindowCompat.setDecorFitsSystemWindows(window, false)
                } else {
                    applyWindowColors(window, color.toArgb(), statusBar = false)
                }
            }
        } else {
            val color = colorScheme.navigationBarColor?.toArgb() ?: drawableProvider?.keyboardColor

            window.window?.let { window ->
                if(color == null || !isNavigationBarVisible) {
                    applyWindowColors(window, Color.TRANSPARENT, statusBar = false)
                } else {
                    applyWindowColors(window, color, statusBar = false)
                }
            }
        }
    }

    private fun updateDrawableProvider(colorScheme: KeyboardColorScheme) {
        activeColorScheme.value = colorScheme
        drawableProvider = BasicThemeProvider(this, colorScheme)

        updateNavigationBarVisibility()
        uixManager.onColorSchemeChanged()
    }

    override fun getDrawableProvider(): DynamicThemeProvider {
        return drawableProvider ?: BasicThemeProvider(this, colorScheme).let {
            drawableProvider = it
            it
        }
    }

    private fun updateColorsIfDynamicChanged() {
        if(activeThemeOption?.dynamic == true) {
            val currColors = colorScheme
            val nextColors = activeThemeOption!!.obtainColors(this)

            if(currColors.differsFrom(nextColors)) {
                updateDrawableProvider(nextColors)
                recreateKeyboard()
                return
            }
        }

        if(drawableProvider?.displayDpi != resources.displayMetrics.densityDpi) {
            updateDrawableProvider(colorScheme)
            recreateKeyboard()
            return
        }
    }

    private fun onSizeMaybeUpdated() {
        if(sizingCalculator.didMaybeChange(size.value)) {
            onSizeUpdated()
        }
    }

    fun onSizeUpdated() {
        val newSize = calculateSize() ?: return
        val shouldInvalidateKeyboard = size.value?.let { oldSize ->
            when {
                oldSize is FloatingKeyboardSize && newSize is FloatingKeyboardSize -> {
                    oldSize.width != newSize.width || oldSize.height != newSize.height
                }
                else -> !newSize.dimensionsSameAs(oldSize)
            }
        } ?: true

        size.value = newSize

        if(shouldInvalidateKeyboard) {
            invalidateKeyboard(true)
            updateNavigationBarVisibility()
        }
    }

    fun invalidateKeyboard(refreshSettings: Boolean = false) {
        if(destroying) return
        size.value = calculateSize()
        updateNavigationBarVisibility()
        settingsRefreshRequired = settingsRefreshRequired || refreshSettings

        if(!uixManager.isMainKeyboardHidden) {
            recreateKeyboard()
        } else {
            pendingRecreateKeyboard = true
        }
    }

    fun onKeyboardShown() {
        if(pendingRecreateKeyboard) {
            pendingRecreateKeyboard = false
            recreateKeyboard()
        } else {
            latinIMELegacy.mKeyboardSwitcher?.mainKeyboardView?.invalidateAllKeys()
        }
    }

    private var currentSubtype = ""

    val jobs = mutableListOf<Job>()
    private fun launchJob(task: suspend CoroutineScope.() -> Unit) {
        jobs.add(lifecycleScope.launch(block = task))
    }

    private fun stopJobs() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private var unlockReceiver = UnlockedBroadcastReceiver { onDeviceUnlocked() }

    override fun onCreate() {
        super.onCreate()

        UserBinaryDictionary.resetUserDictionariesRequiringRecreation()

        CanThrowIfDebug = isDirectBootUnlocked

        JniUtils.loadNativeLibrary()

        LayoutManager.init(this)

        DataStoreHelper.init(this)

        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        registerReceiver(unlockReceiver, filter)

        Subtypes.addDefaultSubtypesIfNecessary(this)

        getSettingBlocking(THEME_KEY).let {
            val themeOption = getThemeOption(this, it).orDefault(this@LatinIME)

            activeThemeOption = themeOption
            activeColorScheme.value = themeOption.obtainColors(this@LatinIME)
        }

        imeManager.onCreate()
        latinIMELegacy.onCreate()

        scheduleUpdateCheckingJob(this)
        launchJob { uixManager.showUpdateNoticeIfNeeded() }

        launchJob {
            getSettingFlow(THEME_KEY).collect {
                val themeOption = getThemeOption(this@LatinIME, it).orDefault(this@LatinIME)

                activeThemeOption = themeOption
                activeColorScheme.value = themeOption.obtainColors(this@LatinIME)

                updateDrawableProvider(activeColorScheme.value)
                invalidateKeyboard()
            }
        }

        launchJob {
            combine(
                getSettingFlow(HiddenKeysSetting),
                getSettingFlow(KeyBordersSetting),
                getSettingFlow(KeyHintsSetting)
            ) { a, b, c -> Triple(a, b, c) }.collect {
                    drawableProvider?.let { provider ->
                        if(provider is BasicThemeProvider) {
                            activeThemeOption?.obtainColors?.let { f ->
                                updateDrawableProvider(f(this@LatinIME))
                                invalidateKeyboard()
                            }
                        }
                    }
                }
        }

        launchJob {
            val onNewSubtype: suspend (String) -> Unit = {
                val activeSubtype = it.ifEmpty {
                    getSettingBlocking(SubtypesSetting).firstOrNull()
                }

                if(activeSubtype != null && activeSubtype != currentSubtype) {
                    withContext(Dispatchers.Main) {
                        changeSubtype(activeSubtype)
                    }
                }
            }

            onNewSubtype(getSetting(ActiveSubtype))

            dataStore.data.collect {
                onNewSubtype(it[ActiveSubtype.key] ?: ActiveSubtype.default)
            }
        }

        launchJob {
            getSettingFlow(SubtypesSetting).collect {
                Subtypes.updateLanguageOnSpaceBarVisibility(this@LatinIME)
            }
        }

        launchJob {
            dataStore.data.collect {
                CrashLoggingApplication.logPreferences(it)
            }
        }

        launchJob {
            val prev: MutableMap<KeyboardSizeSettingKind, String?> =
                KeyboardSizeSettingKind.entries.associateWith { null }.toMutableMap()

            dataStore.data.collect { data ->
                prev.keys.toList().forEach {
                    if(data[KeyboardSettings[it]!!.key] != prev[it]) {
                        prev[it] = data[KeyboardSettings[it]!!.key]
                        onSizeUpdated()
                    }
                }
            }
        }

        uixManager.onCreate()

        Settings.getInstance().settingsChangedListeners.add { oldSettings, newSettings ->
            val differs = (oldSettings.mActionKeyId != newSettings.mActionKeyId)
                    || (oldSettings.mShowsActionKey != newSettings.mShowsActionKey)

            if (differs) {
                invalidateKeyboard(refreshSettings = true)
            }
        }
    }

    fun changeSubtype(subtypeString: String) {
        if(currentSubtype == subtypeString) return
        currentSubtype = subtypeString

        val subtype = Subtypes.convertToSubtype(subtypeString)
        changeInputMethodSubtype(subtype)
        uixManager.updateLocale(Subtypes.getLocale(subtype))
    }

    private var destroying = false
    override fun onDestroy() {
        destroying = true
        unregisterReceiver(unlockReceiver)
        stopJobs()
        viewModelStore.clear()
        uixManager.onDestroy()
        imeManager.onDestroy()
        Settings.getInstance().settingsChangedListeners.clear()
        latinIMELegacy.onDestroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        Log.w("LatinIME", "Configuration changed")
        size.value = calculateSize()
        updateNavigationBarVisibility()
        latinIMELegacy.onConfigurationChanged(newConfig)
        super.onConfigurationChanged(newConfig)
        uixManager.updateLocaleOnCfgChanged()
    }

    override fun onInitializeInterface() {
        latinIMELegacy.onInitializeInterface()
    }

    private var legacyInputView: MutableState<View?> = mutableStateOf(null)
    override fun onCreateInputView(): View {
        val composeView = super.onCreateInputView()

        legacyInputView.value = latinIMELegacy.onCreateInputView()
        latinIMELegacy.setComposeInputView(composeView)

        uixManager.setContent()

        window.window?.decorView?.setOnApplyWindowInsetsListener { v, insets ->
            window.window!!.decorView.post { onSizeUpdated() }
            window.window!!.decorView.postDelayed({ onSizeMaybeUpdated() }, 250L)
            v.onApplyWindowInsets(insets)
        }

        return composeView
    }

    private var inputViewHeight: Int = -1

    fun getInputViewHeight(): Int = inputViewHeight
    fun getViewHeight(): Int = composeView?.height ?: resources.displayMetrics.heightPixels
    fun getViewWidth(): Int = composeView?.width ?: resources.displayMetrics.widthPixels

    private var isInputModal = false
    fun setInputModal(to: Boolean) {
        isInputModal = to
    }

    @Composable
    internal fun LegacyKeyboardView(modifier: Modifier, hidden: Boolean) {
        val config = LocalConfiguration.current
        val isHardKeyboardConnected = config.keyboard != Configuration.KEYBOARD_NOKEYS &&
                config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

        val actuallyHidden = hidden || isHardKeyboardConnected

        val finalModifier = if(actuallyHidden) {
            modifier
                .clipToBounds()
                .size(0.dp)
                .onSizeChanged {
                    inputViewHeight = it.height
                }
        } else {
            modifier.onSizeChanged {
                inputViewHeight = it.height
            }
        }.safeKeyboardPadding()

        val legacyInputView = legacyInputView.value
        key(legacyInputView) {
            AndroidView(factory = {
                legacyInputView!!.also {
                    if(it.parent != null) (it.parent as ViewGroup).removeView(it)
                }
            }, modifier = finalModifier, onRelease = {
                val view = it as InputView
                view.deallocateMemory()
                view.removeAllViews()
            })
        }
    }

    fun updateLegacyView(newView: View) {
        legacyInputView.value = newView
        composeView?.let {
            latinIMELegacy.setComposeInputView(it)
        }

        latinIMELegacy.setInputView(newView)
    }

    override fun setInputView(view: View?) {
        super.setInputView(view)

        composeView?.let {
            latinIMELegacy.setComposeInputView(it)
        }

        latinIMELegacy.setInputView(legacyInputView.value)
    }

    override fun setCandidatesView(view: View?) {
        return latinIMELegacy.setCandidatesView(view)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        latinIMELegacy.onStartInput(attribute, restarting)
        uixManager.inputStarted(attribute)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        onSizeMaybeUpdated()
        imeManager.onStartInput()
        latinIMELegacy.onStartInputView(info, restarting)
        lifecycleScope.launch { uixManager.showUpdateNoticeIfNeeded() }
        updateColorsIfDynamicChanged()
        uixManager.updateEmojiTranslationsIfNeeded()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        latinIMELegacy.onFinishInputView(finishingInput)
        uixManager.onInputFinishing()
        imeManager.onFinishInput()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        latinIMELegacy.onFinishInput()
        uixManager.onInputFinishing()
    }

    private fun changeInputMethodSubtype(newSubtype: InputMethodSubtype?) {
        latinIMELegacy.onCurrentInputMethodSubtypeChanged(newSubtype)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        latinIMELegacy.onWindowShown()
        updateColorsIfDynamicChanged()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        latinIMELegacy.onWindowHidden()
        uixManager.onInputFinishing()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )

        latinIMELegacy.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
    }

    override fun onExtractedTextClicked() {
        latinIMELegacy.onExtractedTextClicked()
        super.onExtractedTextClicked()
    }

    override fun onExtractedCursorMovement(dx: Int, dy: Int) {
        latinIMELegacy.onExtractedCursorMovement(dx, dy)
        super.onExtractedCursorMovement(dx, dy)
    }

    override fun hideWindow() {
        latinIMELegacy.hideWindow()
        super.hideWindow()
    }

    override fun onDisplayCompletions(completions: Array<out CompletionInfo>?) {

    }

    fun getNavigationBarBottomInset(view: View): Int {
        val windowInsets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(view)
        if (windowInsets != null) {
            val navBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            return navBarInsets.bottom
        }
        return 0
    }

    override fun onComputeInsets(outInsets: Insets?) {
        if (legacyInputView.value == null || composeView == null) {
            return
        }

        val viewHeight = composeView!!.height
        val size = size.value ?: return
        latinIMELegacy.setInsets(outInsets!!.apply {
            when(size) {
                is FloatingKeyboardSize -> {
                    val bottomInset = getNavigationBarBottomInset(composeView!!)
                    val height = uixManager.touchableHeight

                    val left   = uixManager.floatingPosition.x.toInt()
                    val right  = (uixManager.floatingPosition.x + size.width).roundToInt()
                    val top    = uixManager.floatingPosition.y.toInt() - uixManager.extraTopTouchHeight
                    val bottom = (uixManager.floatingPosition.y + height).roundToInt()

                    touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    touchableRegion.set(left, top, right, bottom)
                    contentTopInsets = viewHeight - bottomInset
                    visibleTopInsets = viewHeight - bottomInset
                }
                else -> {
                    touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE

                    val touchableHeight = uixManager.touchableHeight
                    val topInset = if(touchableHeight < 1 || touchableHeight >= viewHeight - 1) {
                        val actionBarHeight = sizingCalculator.calculateTotalActionBarHeightPx()

                        viewHeight - size.height - actionBarHeight
                    } else {
                        viewHeight - touchableHeight
                    }

                    contentTopInsets = topInset
                    visibleTopInsets = topInset - uixManager.extraTopTouchHeight
                }
            }

            if(isInputModal || latinIMELegacy.mKeyboardSwitcher?.isShowingMoreKeysPanel == true) {
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                touchableRegion.set(0, 0, composeView!!.width, composeView!!.height)
            }
        })
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        return latinIMELegacy.onShowInputRequested(
            flags,
            configChange
        ) || super.onShowInputRequested(flags, configChange)
    }

    override fun onEvaluateInputViewShown(): Boolean {
        return latinIMELegacy.onEvaluateInputViewShown()
                || super.onEvaluateInputViewShown()
                || !getSetting(HideKeyboardWhenHardKeyboardConnected)
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false 
    }

    override fun updateFullscreenMode() {
        super.updateFullscreenMode()
        latinIMELegacy.updateFullscreenMode()
    }

    // --- КАРТА РУССКИХ КЛАВИШ ДЛЯ ФИЗИЧЕСКОЙ КЛАВИАТУРЫ ---
    private fun getRussianChar(keyCode: Int, isShift: Boolean, isCaps: Boolean): Char? {
        val isUpper = isShift xor isCaps
        return when (keyCode) {
            KeyEvent.KEYCODE_Q -> if (isUpper) 'Й' else 'й'
            KeyEvent.KEYCODE_W -> if (isUpper) 'Ц' else 'ц'
            KeyEvent.KEYCODE_E -> if (isUpper) 'У' else 'у'
            KeyEvent.KEYCODE_R -> if (isUpper) 'К' else 'к'
            KeyEvent.KEYCODE_T -> if (isUpper) 'Е' else 'е'
            KeyEvent.KEYCODE_Y -> if (isUpper) 'Н' else 'н'
            KeyEvent.KEYCODE_U -> if (isUpper) 'Г' else 'г'
            KeyEvent.KEYCODE_I -> if (isUpper) 'Ш' else 'ш'
            KeyEvent.KEYCODE_O -> if (isUpper) 'Щ' else 'щ'
            KeyEvent.KEYCODE_P -> if (isUpper) 'З' else 'з'
            KeyEvent.KEYCODE_LEFT_BRACKET -> if (isUpper) 'Х' else 'х'
            KeyEvent.KEYCODE_RIGHT_BRACKET -> if (isUpper) 'Ъ' else 'ъ'
            KeyEvent.KEYCODE_A -> if (isUpper) 'Ф' else 'ф'
            KeyEvent.KEYCODE_S -> if (isUpper) 'Ы' else 'ы'
            KeyEvent.KEYCODE_D -> if (isUpper) 'В' else 'в'
            KeyEvent.KEYCODE_F -> if (isUpper) 'А' else 'а'
            KeyEvent.KEYCODE_G -> if (isUpper) 'П' else 'п'
            KeyEvent.KEYCODE_H -> if (isUpper) 'Р' else 'р'
            KeyEvent.KEYCODE_J -> if (isUpper) 'О' else 'о'
            KeyEvent.KEYCODE_K -> if (isUpper) 'Л' else 'л'
            KeyEvent.KEYCODE_L -> if (isUpper) 'Д' else 'д'
            KeyEvent.KEYCODE_SEMICOLON -> if (isUpper) 'Ж' else 'ж'
            KeyEvent.KEYCODE_APOSTROPHE -> if (isUpper) 'Э' else 'э'
            KeyEvent.KEYCODE_Z -> if (isUpper) 'Я' else 'я'
            KeyEvent.KEYCODE_X -> if (isUpper) 'Ч' else 'ч'
            KeyEvent.KEYCODE_C -> if (isUpper) 'С' else 'с'
            KeyEvent.KEYCODE_V -> if (isUpper) 'М' else 'м'
            KeyEvent.KEYCODE_B -> if (isUpper) 'И' else 'и'
            KeyEvent.KEYCODE_N -> if (isUpper) 'Т' else 'т'
            KeyEvent.KEYCODE_M -> if (isUpper) 'Ь' else 'ь'
            KeyEvent.KEYCODE_COMMA -> if (isUpper) 'Б' else 'б'
            KeyEvent.KEYCODE_PERIOD -> if (isUpper) 'Ю' else 'ю'
            KeyEvent.KEYCODE_GRAVE -> if (isUpper) 'Ё' else 'ё'
            
            // Знаки препинания и цифры (Caps Lock на них не влияет, только Shift)
            KeyEvent.KEYCODE_SLASH -> if (isShift) ',' else '.'
            KeyEvent.KEYCODE_2 -> if (isShift) '"' else null
            KeyEvent.KEYCODE_3 -> if (isShift) '№' else null
            KeyEvent.KEYCODE_4 -> if (isShift) ';' else null
            KeyEvent.KEYCODE_6 -> if (isShift) ':' else null
            KeyEvent.KEYCODE_7 -> if (isShift) '?' else null
            KeyEvent.KEYCODE_BACKSLASH -> if (isShift) '/' else '\\'
            else -> null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Переключаем язык по Ctrl+Space
        if (event != null && event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_SPACE) {
            Subtypes.switchToNextLanguage(this, 1)?.let { nextSubtype ->
                changeSubtype(nextSubtype)
            }
            return true
        }

        // Если включен русский язык, перехватываем аппаратные клавиши напрямую
        if (event != null && event.deviceId > 0 && currentSubtype.contains("ru")) {
            val isShift = event.isShiftPressed
            val isCaps = event.isCapsLockOn
            
            val ruChar = getRussianChar(keyCode, isShift, isCaps)
            if (ruChar != null) {
                currentInputConnection?.commitText(ruChar.toString(), 1)
                return true
            }
            // Пробел, Enter, Backspace и обычные цифры уйдут ниже и обработаются системой
        }

        if (event != null && event.deviceId > 0 && event.unicodeChar != 0) {
            return super.onKeyDown(keyCode, event)
        }

        return latinIMELegacy.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_SPACE) {
            return true
        }
        
        if (event != null && event.deviceId > 0 && currentSubtype.contains("ru")) {
            val isShift = event.isShiftPressed
            val isCaps = event.isCapsLockOn
            if (getRussianChar(keyCode, isShift, isCaps) != null) {
                return true
            }
        }
        
        if (event != null && event.deviceId > 0 && event.unicodeChar != 0) {
            return super.onKeyUp(keyCode, event)
        }
        
        return latinIMELegacy.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
    }
    // ---------------------------------------------------

    override fun updateVisibility(shouldShowSuggestionsStrip: Boolean, fullscreenMode: Boolean) {
        uixManager.updateVisibility(shouldShowSuggestionsStrip, fullscreenMode)
    }

    override fun setSuggestions(
        suggestedWords: SuggestedWords,
        rtlSubtype: Boolean,
        cfg: ExpandableSuggestionBarConfiguration
    ) {
        uixManager.setSuggestions(suggestedWords, rtlSubtype, cfg)

        AccessibilityUtils.getInstance().setAutoCorrection(suggestedWords)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        uixManager.cleanUpPersistentStates()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        uixManager.cleanUpPersistentStates()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        return createInlineSuggestionsRequest(this, colorScheme)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        return uixManager.onInlineSuggestionsResponse(response)
    }

    fun requestForgetWord(suggestedWordInfo: SuggestedWordInfo) {
        uixManager.requestForgetWord(suggestedWordInfo)
    }

    fun blacklistWord(suggestedWordInfo: SuggestedWordInfo?) = lifecycleScope.launch {
        val word = suggestedWordInfo?.mWord
        if(word != null) {
            SuggestionBlacklist.addToBlacklistSetting(this@LatinIME, word)

            val settings = Settings.getInstance().current
            imeManager.getActiveIME(settings).let { ime ->
                if (ime is WordLearner) {
                    SuggestionBlacklist.getCapitalVariants(word, settings.mLocale).forEach {
                        ime.removeFromHistory(
                            it,
                            NgramContext.EMPTY_PREV_WORDS_INFO,
                            -1,
                            Constants.NOT_A_CODE
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    ime.requestSuggestionRefresh()
                }
            }
        }
    }

    fun rememberEmojiSuggestion(suggestion: SuggestedWordInfo) {
        if(suggestion.mKindAndFlags == SuggestedWordInfo.KIND_EMOJI_SUGGESTION) {
            lifecycleScope.launch {
                withContext(Dispatchers.Default) {
                    useEmoji(suggestion.mWord)
                }
            }
        }
    }

    private var overrideInputConnection: InputConnection? = null
    private var overrideEditorInfo: EditorInfo? = null
    fun overrideInputConnection(to: InputConnection?, editorInfo: EditorInfo?) {
        if(to == this.overrideInputConnection && editorInfo == overrideEditorInfo) return

        imeManager.onFinishInput()
        this.overrideInputConnection = to
        this.overrideEditorInfo = editorInfo

        latinIMELegacy.loadSettings()

        imeManager.onStartInput()

        val currentIC = currentInputConnection
        currentIC?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            super.getCurrentInputConnection()?.setImeConsumesInput(to != null)
        }
    }

    val isInputConnectionOverridden
        get() = overrideInputConnection != null

    val inputConnectionOverridenWithSuggestions
        get() = isInputConnectionOverridden && overrideEditorInfo?.let {
            !it.privateImeOptions.contains("org.futo.inputmethod.latin.NoSuggestions=1")
        } ?: false

    override fun getCurrentInputConnection(): InputConnection? {
        return overrideInputConnection ?: super.getCurrentInputConnection()
    }

    override fun getCurrentInputEditorInfo(): EditorInfo? {
        return overrideEditorInfo ?: super.getCurrentInputEditorInfo()
    }

    fun getBaseInputConnection(): InputConnection? {
        return super.getCurrentInputConnection()
    }

    fun getBaseInputEditorInfo(): EditorInfo? {
        return super.getCurrentInputEditorInfo()
    }

    private fun onDeviceUnlocked() {
        forceUnlockDatastore(this)

        Log.i("LatinIME", "Device has been unlocked, reloading settings")

        UserBinaryDictionary.resetUserDictionariesRequiringRecreation()

        Settings.init(applicationContext)
        Settings.getInstance().onSharedPreferenceChanged(null, "")
        latinIMELegacy.loadSettings()
        recreateKeyboard()

        imeManager.onDeviceUnlocked()

        uixManager.onPersistentStatesUnlocked()

        CanThrowIfDebug = true
    }

    fun trySwitchToShortcutIMEorInternal() {
        if(!latinIMELegacy.mRichImm.switchToShortcutIme(this)) {
            uixManager.activateInternalVoiceIME()
        }
    }

    override val foldState: FoldingOptions
        get() = uixManager.foldingOptions.value

    override val currentSizeState: KeyboardSizeSettingKind
        get() = when {
            (foldState.feature != null && isFoldableInnerDisplayAllowed()) ->
                KeyboardSizeSettingKind.FoldableInnerDisplay

            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ->
                KeyboardSizeSettingKind.Landscape

            else ->
                KeyboardSizeSettingKind.Portrait
        }
}
