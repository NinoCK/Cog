package com.fastsearch.assist.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bestassistant.listen.ListenState
import com.fastsearch.R
import com.fastsearch.assist.SearchUiState
import com.fastsearch.assist.TopHit
import com.fastsearch.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private val PanelMaxWidth = 640.dp

/**
 * The assistant overlay: a frosted scrim, a floating search bar and a results card.
 *
 * Motion: the scrim/blur fade in while the bar drops in on a soft spring, the results card
 * follows a beat later, rows animate in place as results change, and predictive back
 * shrinks the whole panel under the user's finger before it springs away.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    textState: TextFieldState,
    session: Int,
    /** Incremented when a command has run and the overlay should animate away. */
    dismissSignal: Int,
    darkTheme: Boolean,
    blurActive: Boolean,
    voiceAvailable: Boolean,
    listen: ListenState,
    onQueryChange: (String) -> Unit,
    onRevealProgress: (Float) -> Unit,
    onDismissed: () -> Unit,
    onSubmit: (TopHit, androidx.compose.ui.geometry.Rect?) -> Unit,
    actions: SearchActions,
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val topHitBounds = remember { BoundsHolder() }

    val reveal = remember { Animatable(0f) }
    val contentReveal = remember { Animatable(0f) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var dismissing by remember { mutableStateOf(false) }

    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }.collect(onQueryChange)
    }
    LaunchedEffect(Unit) {
        snapshotFlow { reveal.value.coerceIn(0f, 1f) * (1f - 0.6f * backProgress) }.collect(onRevealProgress)
    }

    // Every invocation (including re-invocations while open) focuses the field and shows the keyboard.
    LaunchedEffect(session) {
        dismissing = false
        focusRequester.requestFocus()
        keyboard?.show()
        launch { reveal.animateTo(1f, Motion.panelSpring()) }
        delay(45)
        contentReveal.animateTo(1f, Motion.contentSpring())
    }

    fun dismiss() {
        if (dismissing) return
        dismissing = true
        keyboard?.hide()
        scope.launch {
            launch { contentReveal.animateTo(0f, tween(120, easing = Motion.EmphasizedAccelerate)) }
            reveal.animateTo(0f, tween(210, easing = Motion.EmphasizedAccelerate))
            onDismissed()
        }
    }
    val currentDismiss by rememberUpdatedState(::dismiss)
    LaunchedEffect(dismissSignal) { if (dismissSignal > 0) currentDismiss() }

    PredictiveBackHandler(enabled = !dismissing) { progress ->
        try {
            progress.collect { event ->
                backEdge = event.swipeEdge
                backProgress = Motion.EmphasizedDecelerate.transform(event.progress)
            }
            dismiss()
        } catch (e: CancellationException) {
            scope.launch { animate(backProgress, 0f, animationSpec = spring()) { value, _ -> backProgress = value } }
            throw e
        }
    }

    val colors = MaterialTheme.colorScheme
    val scrimColor = if (darkTheme) Color.Black else colors.surface
    val scrimAlpha = when {
        blurActive && darkTheme -> 0.45f
        blurActive -> 0.55f
        darkTheme -> 0.74f
        else -> 0.82f
    }
    val barColor = if (darkTheme) colors.surfaceContainerHigh else colors.surfaceContainerLowest
    val panelColor = if (darkTheme) colors.surfaceContainer else colors.surfaceContainerLowest

    Box(Modifier.fillMaxSize()) {
        // Scrim: tapping anywhere outside the panel dismisses.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal.value.coerceIn(0f, 1f) * (1f - 0.4f * backProgress) }
                .background(scrimColor.copy(alpha = scrimAlpha))
                .pointerInput(Unit) { detectTapGestures { currentDismiss() } },
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .graphicsLayer {
                    val r = reveal.value
                    val scale = (0.94f + 0.06f * r) * (1f - 0.08f * backProgress)
                    alpha = r.coerceIn(0f, 1f)
                    scaleX = scale
                    scaleY = scale
                    translationY = (1f - r) * -28.dp.toPx() + backProgress * 8.dp.toPx()
                    translationX = backProgress * 10.dp.toPx() * if (backEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
        ) {
            SearchBar(
                textState = textState,
                focusRequester = focusRequester,
                containerColor = barColor,
                voiceAvailable = voiceAvailable,
                listen = listen,
                onSubmit = { state.topHit?.let { onSubmit(it, topHitBounds.rect) } },
                onVoice = actions::toggleListening,
                onSettings = actions::openSettings,
                modifier = Modifier.widthIn(max = PanelMaxWidth).fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            ResultsPanel(
                state = state,
                textState = textState,
                containerColor = panelColor,
                topHitBounds = topHitBounds,
                actions = actions,
                onUserScroll = { keyboard?.hide() },
                modifier = Modifier
                    .widthIn(max = PanelMaxWidth)
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .graphicsLayer {
                        val c = contentReveal.value
                        alpha = c.coerceIn(0f, 1f)
                        translationY = (1f - c) * -16.dp.toPx()
                    },
            )
        }
    }
}

@Composable
private fun SearchBar(
    textState: TextFieldState,
    focusRequester: FocusRequester,
    containerColor: Color,
    voiceAvailable: Boolean,
    listen: ListenState,
    onSubmit: () -> Unit,
    onVoice: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isEmpty by remember { derivedStateOf { textState.text.isEmpty() } }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, fontSize = 18.sp)

    Surface(
        shape = RoundedCornerShape(30.dp),
        color = containerColor,
        shadowElevation = 8.dp,
        modifier = modifier.height(60.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 6.dp),
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.primary)
            Spacer(Modifier.width(14.dp))
            BasicTextField(
                state = textState,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                textStyle = textStyle,
                cursorBrush = SolidColor(colors.primary),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                onKeyboardAction = { onSubmit() },
                decorator = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (isEmpty) {
                            val hint = when (listen) {
                                is ListenState.Listening -> R.string.listening_hint
                                ListenState.Processing -> R.string.processing_hint
                                ListenState.Idle -> R.string.search_hint
                            }
                            Text(stringResource(hint), style = textStyle, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                        innerTextField()
                    }
                },
            )
            val listening = listen != ListenState.Idle
            AnimatedContent(
                targetState = when {
                    listening -> BarActions.LISTENING
                    isEmpty -> BarActions.EMPTY
                    else -> BarActions.TYPED
                },
                transitionSpec = {
                    (fadeIn(tween(150)) + scaleIn(initialScale = 0.7f)) togetherWith (fadeOut(tween(100)) + scaleOut(targetScale = 0.7f))
                },
                contentAlignment = Alignment.CenterEnd,
                label = "barActions",
            ) { mode ->
                if (mode == BarActions.LISTENING) {
                    PulsingMic(listen, onClick = onVoice)
                } else if (mode == BarActions.EMPTY) {
                    Row {
                        if (voiceAvailable) {
                            IconButton(onClick = onVoice) {
                                Icon(Icons.Rounded.Mic, contentDescription = stringResource(R.string.action_voice), tint = colors.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.action_settings), tint = colors.onSurfaceVariant)
                        }
                    }
                } else {
                    IconButton(onClick = { textState.clearText() }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear), tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private enum class BarActions { EMPTY, TYPED, LISTENING }

/**
 * The microphone while it records: a filled circle that swells with the voice, inside two rings
 * that keep rippling outwards. Tapping it stops listening and uses what was heard.
 */
@Composable
private fun PulsingMic(listen: ListenState, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val target = (listen as? ListenState.Listening)?.level ?: 0f
    val level by animateFloatAsState(target, spring(stiffness = Spring.StiffnessMediumLow), label = "micLevel")
    val processing = listen == ListenState.Processing
    val ripple = rememberInfiniteTransition(label = "micRipple")
    val phase by ripple.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (processing) 2200 else 1400, easing = LinearEasing), RepeatMode.Restart),
        label = "micPhase",
    )
    val description = stringResource(R.string.action_stop_listening)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(48.dp)) {
            val base = 13.dp.toPx()
            val spread = 11.dp.toPx()
            for (p in floatArrayOf(phase, (phase + 0.5f) % 1f)) {
                drawCircle(colors.primary.copy(alpha = (1f - p) * 0.32f), radius = base + p * spread)
            }
            val swell = if (processing) 0f else level * 5.dp.toPx()
            drawCircle(colors.primary.copy(alpha = if (processing) 0.7f else 1f), radius = base + swell)
        }
        Icon(Icons.Rounded.Mic, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
    }
}

/** Whether the results list should stay scrolled to the top for the current query. */
private class TopPin {
    var query: String? = null
    var pinned = true
}

@Composable
private fun ResultsPanel(
    state: SearchUiState,
    textState: TextFieldState,
    containerColor: Color,
    topHitBounds: BoundsHolder,
    actions: SearchActions,
    onUserScroll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(state) { buildRows(state) }
    // Keep showing the last rows while the card animates away, instead of an empty card.
    var visibleRows by remember { mutableStateOf(rows) }
    if (rows.isNotEmpty()) visibleRows = rows

    val listState = rememberLazyListState()
    val pin = remember { TopPin() }
    if (pin.query != state.query) {
        pin.query = state.query
        pin.pinned = true
    }
    // Results arrive at different times (contacts, answers, suggestions). Rows inserted above the
    // first visible item would otherwise land off-screen, because the list anchors on item keys;
    // until the user scrolls, keep the list pinned to its first row.
    if (pin.pinned) listState.requestScrollToItem(0)

    val currentOnUserScroll by rememberUpdatedState(onUserScroll)
    val hideKeyboardOnDrag = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && abs(available.y) > 2f) {
                    pin.pinned = false
                    currentOnUserScroll()
                }
                return Offset.Zero
            }
        }
    }

    AnimatedVisibility(
        visible = rows.isNotEmpty(),
        enter = fadeIn(tween(160)) + expandVertically(Motion.layoutSpring(), expandFrom = Alignment.Top),
        exit = fadeOut(tween(120)) + shrinkVertically(tween(180), shrinkTowards = Alignment.Top),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = containerColor,
            shadowElevation = 4.dp,
            modifier = Modifier.animateContentSize(Motion.layoutSpring()),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 4.dp, bottom = 10.dp),
                modifier = Modifier.nestedScroll(hideKeyboardOnDrag),
            ) {
                items(visibleRows, key = { it.key }, contentType = { it.contentType }) { row ->
                    Box(
                        Modifier.animateItem(
                            fadeInSpec = tween(220),
                            placementSpec = Motion.layoutSpring(),
                            fadeOutSpec = tween(100),
                        ),
                    ) {
                        ResultRowContent(row, state, textState, topHitBounds, actions)
                    }
                }
            }
        }
    }
}
