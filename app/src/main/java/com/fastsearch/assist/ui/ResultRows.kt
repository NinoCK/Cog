package com.fastsearch.assist.ui

import android.view.HapticFeedbackConstants
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bestassistant.commands.Command
import com.fastsearch.R
import com.fastsearch.assist.AgendaUi
import com.fastsearch.assist.CallsUi
import com.fastsearch.assist.CurrencyUi
import com.fastsearch.assist.DefinitionUi
import com.fastsearch.assist.SearchUiState
import com.fastsearch.assist.TopHit
import com.fastsearch.assist.WeatherUi
import com.fastsearch.assist.WikiUi
import com.fastsearch.data.AppEntry
import com.fastsearch.data.Contact
import com.fastsearch.data.InstantAnswer
import com.fastsearch.data.SearchEngine
import com.fastsearch.search.Calculator

const val APP_COLUMNS = 5

/** One entry in the results list. Keys are stable so the list can animate changes. */
sealed interface ResultRow {
    val key: String
    val contentType: String get() = this::class.simpleName.orEmpty()

    data class Header(@param:StringRes val title: Int) : ResultRow {
        override val key = "header-$title"
    }

    data class Apps(val apps: List<AppEntry>, val index: Int, val suggested: Boolean) : ResultRow {
        override val key = (if (suggested) "suggested-" else "apps-") + index
    }

    data class Calculation(val result: Calculator.Result) : ResultRow {
        override val key = "calc"
    }

    data class Url(val url: String) : ResultRow {
        override val key = "url"
    }

    data class ContactItem(val contact: Contact) : ResultRow {
        override val key = "contact-${contact.id}"
    }

    data object ContactsPrompt : ResultRow {
        override val key = "contacts-prompt"
    }

    data class Answer(val answer: InstantAnswer) : ResultRow {
        override val key = "answer"
    }

    data class WebSearch(val query: String, val engine: SearchEngine) : ResultRow {
        override val key = "web"
    }

    data class Suggestion(val text: String) : ResultRow {
        override val key = "suggestion-$text"
    }

    /** An assistant command: what will happen, ready to run. */
    data class CommandItem(val command: Command, val contact: Contact?) : ResultRow {
        override val key = "command"
    }

    data class Weather(val command: Command.Weather, val weather: WeatherUi) : ResultRow {
        override val key = "weather"
    }

    /** "what is ..." answered from Wikipedia. */
    data class Wiki(val command: Command.Lookup, val wiki: WikiUi) : ResultRow {
        override val key = "wiki"
    }

    data class Currency(val command: Command.Currency, val ui: CurrencyUi) : ResultRow {
        override val key = "currency"
    }

    data class Definition(val command: Command.Define, val ui: DefinitionUi) : ResultRow {
        override val key = "definition"
    }

    data class Agenda(val command: Command, val ui: AgendaUi) : ResultRow {
        override val key = "agenda"
    }

    data class Calls(val command: Command, val ui: CallsUi) : ResultRow {
        override val key = "calls"
    }

    data object Examples : ResultRow {
        override val key = "examples"
    }
}

fun buildRows(state: SearchUiState): List<ResultRow> = buildList {
    if (state.query.isEmpty()) {
        if (state.suggestedApps.isNotEmpty()) {
            add(ResultRow.Header(R.string.section_suggested))
            state.suggestedApps.chunked(APP_COLUMNS).forEachIndexed { i, chunk -> add(ResultRow.Apps(chunk, i, suggested = true)) }
        }
        if (state.showExamples) {
            add(ResultRow.Header(R.string.section_examples))
            add(ResultRow.Examples)
        }
        return@buildList
    }
    val command = state.command
    when {
        command is Command.Weather -> state.weather?.let { add(ResultRow.Weather(command, it)) }
        command is Command.Lookup -> state.wiki?.let { add(ResultRow.Wiki(command, it)) }
        command is Command.Currency -> state.currency?.let { add(ResultRow.Currency(command, it)) }
        command is Command.Define -> state.definition?.let { add(ResultRow.Definition(command, it)) }
        command is Command.Agenda || command == Command.NextEvent -> state.agenda?.let { add(ResultRow.Agenda(command, it)) }
        command == Command.MissedCalls -> state.calls?.let { add(ResultRow.Calls(command, it)) }
        command is Command.OpenApp -> Unit
        command != null && command.needsContact -> state.commandContact?.let { add(ResultRow.CommandItem(command, it)) }
        command != null -> add(ResultRow.CommandItem(command, null))
    }
    state.calculation?.let { add(ResultRow.Calculation(it)) }
    state.url?.let { add(ResultRow.Url(it)) }
    if (state.apps.isNotEmpty()) {
        add(ResultRow.Header(R.string.section_apps))
        state.apps.chunked(APP_COLUMNS).forEachIndexed { i, chunk -> add(ResultRow.Apps(chunk, i, suggested = false)) }
    }
    if (state.contacts.isNotEmpty() || state.showContactsPrompt) {
        add(ResultRow.Header(R.string.section_contacts))
        state.contacts.forEach { add(ResultRow.ContactItem(it)) }
        if (state.showContactsPrompt) add(ResultRow.ContactsPrompt)
    }
    state.answer?.let { add(ResultRow.Answer(it)) }
    add(ResultRow.Header(R.string.section_web))
    add(ResultRow.WebSearch(state.query, state.engine))
    state.webSuggestions.distinct().forEach { add(ResultRow.Suggestion(it)) }
}

@Composable
fun ResultRowContent(
    row: ResultRow,
    state: SearchUiState,
    textState: TextFieldState,
    topHitBounds: BoundsHolder,
    actions: SearchActions,
) {
    val topHit = state.topHit
    when (row) {
        is ResultRow.Header -> SectionHeader(row.title)
        is ResultRow.Apps -> AppGridRow(
            apps = row.apps,
            highlightedKey = (topHit as? TopHit.App)?.entry?.key,
            topHitBounds = topHitBounds,
            actions = actions,
        )
        is ResultRow.Calculation -> CalculationRow(row.result, topHit is TopHit.Calculation, topHitBounds, actions)
        is ResultRow.Url -> UrlRow(row.url, topHit is TopHit.Url, topHitBounds, actions)
        is ResultRow.ContactItem -> ContactRow(
            contact = row.contact,
            highlighted = (topHit as? TopHit.ContactHit)?.contact?.id == row.contact.id,
            topHitBounds = topHitBounds,
            actions = actions,
        )
        ResultRow.ContactsPrompt -> ContactsPromptRow(actions)
        is ResultRow.Answer -> AnswerRow(row.answer, actions)
        is ResultRow.WebSearch -> WebSearchRow(row.query, row.engine, topHit is TopHit.Web, topHitBounds, actions)
        is ResultRow.Suggestion -> SuggestionRow(
            text = row.text,
            query = state.query,
            onSearch = { bounds -> actions.webSearch(row.text, bounds) },
            onFill = { textState.setTextAndPlaceCursorAtEnd(row.text) },
        )
        is ResultRow.CommandItem -> CommandRow(
            command = row.command,
            contact = row.contact,
            highlighted = topHit is TopHit.Run || topHit is TopHit.Reach || topHit is TopHit.Info,
            topHitBounds = topHitBounds,
            actions = actions,
        )
        is ResultRow.Weather -> WeatherCard(row.command, row.weather, actions)
        is ResultRow.Wiki -> WikiCard(row.command, row.wiki, actions)
        is ResultRow.Currency -> CurrencyCard(row.command, row.ui, topHit is TopHit.Info, topHitBounds, actions)
        is ResultRow.Definition -> DefinitionCard(row.command, row.ui, actions)
        is ResultRow.Agenda -> AgendaCard(row.command, row.ui, actions)
        is ResultRow.Calls -> CallsCard(row.command, row.ui, actions)
        ResultRow.Examples -> ExamplesRow(onPick = { textState.setTextAndPlaceCursorAtEnd(it) })
    }
}

@Composable
internal fun SectionHeader(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 4.dp),
    )
}

/** Highlight behind the result that the keyboard's action key will open. */
@Composable
internal fun highlightColor(highlighted: Boolean): Color {
    val color by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(180),
        label = "highlight",
    )
    return color
}

/** Shared shell for list-style rows: rounded, inset, highlightable, with a leading badge. */
@Composable
internal fun ListRow(
    highlighted: Boolean,
    bounds: BoundsHolder?,
    onClick: (() -> Unit)?,
    leading: @Composable () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(highlightColor(highlighted))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .trackBounds(bounds)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f)) { content() }
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}

@Composable
internal fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AppGridRow(
    apps: List<AppEntry>,
    highlightedKey: String?,
    topHitBounds: BoundsHolder,
    actions: SearchActions,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        for (i in 0 until APP_COLUMNS) {
            val entry = apps.getOrNull(i)
            Box(Modifier.weight(1f)) {
                if (entry != null) {
                    AppCell(
                        entry = entry,
                        highlighted = entry.key == highlightedKey,
                        topHitBounds = topHitBounds,
                        actions = actions,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(
    entry: AppEntry,
    highlighted: Boolean,
    topHitBounds: BoundsHolder,
    actions: SearchActions,
) {
    val view = LocalView.current
    val iconBounds = remember(entry.key) { BoundsHolder() }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "pressScale")
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(highlightColor(highlighted))
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { actions.launchApp(entry, iconBounds.rect) },
                    onLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        menuOpen = true
                    },
                )
                .padding(top = 10.dp, bottom = 8.dp, start = 2.dp, end = 2.dp),
        ) {
            AppIcon(
                entry = entry,
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .trackBounds(iconBounds)
                    .trackBounds(if (highlighted) topHitBounds else null),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = RoundedCornerShape(16.dp),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_app_info)) },
                leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    actions.appInfo(entry, iconBounds.rect)
                },
            )
            if (!entry.isSystemApp) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_uninstall)) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        actions.uninstall(entry)
                    },
                )
            }
        }
    }
}

@Composable
fun AppIcon(entry: AppEntry, modifier: Modifier = Modifier) {
    val images = LocalImageSource.current
    FadingImage(
        key = entry.key,
        cached = { images.cachedAppIcon(entry) },
        load = { images.appIcon(entry) },
        placeholder = {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        },
        modifier = modifier,
    )
}

/** Shows a cached bitmap immediately, or a placeholder that cross-fades into the loaded one. */
@Composable
private fun FadingImage(
    key: Any,
    cached: () -> ImageBitmap?,
    load: suspend () -> ImageBitmap?,
    placeholder: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    var bitmap by remember(key) { mutableStateOf(cached()) }
    val alpha = remember(key) { Animatable(if (bitmap != null) 1f else 0f) }
    LaunchedEffect(key) {
        if (bitmap == null) {
            bitmap = load()
            alpha.animateTo(1f, tween(200))
        }
    }
    Box(modifier) {
        if (alpha.value < 1f) placeholder()
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha.value },
            )
        }
    }
}

@Composable
private fun CalculationRow(
    result: Calculator.Result,
    highlighted: Boolean,
    topHitBounds: BoundsHolder,
    actions: SearchActions,
) {
    val colors = MaterialTheme.colorScheme
    ListRow(
        highlighted = highlighted,
        bounds = if (highlighted) topHitBounds else null,
        onClick = { actions.copy(result.plain) },
        leading = { IconBadge(Icons.Rounded.Calculate, colors.primary, colors.onPrimary) },
        trailing = {
            IconButton(onClick = { actions.copy(result.plain) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.action_copy))
            }
        },
    ) {
        Column {
            Text(
                text = if (result.usesDegrees) stringResource(R.string.calc_degrees, result.expression) else result.expression,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedContent(
                targetState = result.formatted,
                transitionSpec = {
                    (fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 3 }) togetherWith
                        fadeOut(tween(90)) using SizeTransform(clip = false)
                },
                label = "calcValue",
            ) { value ->
                Text(
                    text = "= $value",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun UrlRow(url: String, highlighted: Boolean, topHitBounds: BoundsHolder, actions: SearchActions) {
    val colors = MaterialTheme.colorScheme
    val bounds = remember { BoundsHolder() }
    ListRow(
        highlighted = highlighted,
        bounds = if (highlighted) topHitBounds else bounds,
        onClick = { actions.openUrl(url, (if (highlighted) topHitBounds else bounds).rect) },
        leading = { IconBadge(Icons.Rounded.Language, colors.primaryContainer, colors.onPrimaryContainer) },
        trailing = {
            Icon(
                Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp).size(20.dp),
            )
        },
    ) {
        Column {
            Text(stringResource(R.string.open_link), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(
                text = url.removePrefix("https://").removePrefix("http://"),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    highlighted: Boolean,
    topHitBounds: BoundsHolder,
    actions: SearchActions,
) {
    val colors = MaterialTheme.colorScheme
    val bounds = remember(contact.id) { BoundsHolder() }
    val quickActions: (@Composable () -> Unit)? = if (contact.number != null) {
        {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                FilledTonalIconButton(
                    onClick = { actions.message(contact, bounds.rect) },
                    // The default tonal container matches the top-hit highlight; keep the button visible on it.
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (highlighted) colors.surface else colors.secondaryContainer,
                    ),
                ) {
                    Icon(Icons.Rounded.Sms, contentDescription = stringResource(R.string.action_message), modifier = Modifier.size(20.dp))
                }
                FilledIconButton(onClick = { actions.call(contact, bounds.rect) }) {
                    Icon(Icons.Rounded.Call, contentDescription = stringResource(R.string.action_call), modifier = Modifier.size(20.dp))
                }
            }
        }
    } else {
        null
    }
    ListRow(
        highlighted = highlighted,
        bounds = if (highlighted) topHitBounds else bounds,
        onClick = { actions.openContact(contact, (if (highlighted) topHitBounds else bounds).rect) },
        leading = { ContactAvatar(contact) },
        trailing = quickActions,
    ) {
        Column {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            contact.number?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun ContactAvatar(contact: Contact) {
    val colors = MaterialTheme.colorScheme
    val palette = listOf(
        colors.primaryContainer to colors.onPrimaryContainer,
        colors.secondaryContainer to colors.onSecondaryContainer,
        colors.tertiaryContainer to colors.onTertiaryContainer,
    )
    val (container, content) = palette[(contact.name.hashCode() and Int.MAX_VALUE) % palette.size]
    val initials: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = contact.name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = content,
            )
        }
    }
    val photo = contact.photoUri
    if (photo == null) {
        Box(Modifier.size(42.dp)) { initials() }
    } else {
        val images = LocalImageSource.current
        FadingImage(
            key = photo,
            cached = { images.cachedPhoto(photo) },
            load = { images.photo(photo) },
            placeholder = initials,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(42.dp).clip(CircleShape),
        )
    }
}

@Composable
private fun ContactsPromptRow(actions: SearchActions) {
    val colors = MaterialTheme.colorScheme
    ListRow(
        highlighted = false,
        bounds = null,
        onClick = null,
        leading = { IconBadge(Icons.Rounded.Contacts, colors.secondaryContainer, colors.onSecondaryContainer) },
        trailing = {
            TextButton(onClick = actions::requestContactsPermission) { Text(stringResource(R.string.action_allow)) }
        },
    ) {
        Column {
            Text(stringResource(R.string.contacts_prompt_title), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            Text(stringResource(R.string.contacts_prompt_body), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnswerRow(answer: InstantAnswer, actions: SearchActions) {
    val colors = MaterialTheme.colorScheme
    val bounds = remember { BoundsHolder() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceContainerHighest.copy(alpha = 0.6f))
            .then(if (answer.url != null) Modifier.clickable { actions.openUrl(answer.url, bounds.rect) } else Modifier)
            .trackBounds(bounds)
            .padding(16.dp),
    ) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(
                    text = answer.heading,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = answer.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            answer.imageUrl?.let { url ->
                Spacer(Modifier.width(12.dp))
                val images = LocalImageSource.current
                FadingImage(
                    key = url,
                    cached = { images.cachedWebImage(url) },
                    load = { images.webImage(url) },
                    placeholder = {},
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.answer_attribution, answer.source),
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
        )
    }
}

@Composable
private fun WebSearchRow(
    query: String,
    engine: SearchEngine,
    highlighted: Boolean,
    topHitBounds: BoundsHolder,
    actions: SearchActions,
) {
    val colors = MaterialTheme.colorScheme
    val bounds = remember { BoundsHolder() }
    ListRow(
        highlighted = highlighted,
        bounds = if (highlighted) topHitBounds else bounds,
        onClick = { actions.webSearch(query, (if (highlighted) topHitBounds else bounds).rect) },
        leading = { IconBadge(Icons.Rounded.TravelExplore, colors.tertiaryContainer, colors.onTertiaryContainer) },
        trailing = {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        },
    ) {
        Column {
            Text(
                text = "“$query”",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.search_with, stringResource(engine.label)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SuggestionRow(
    text: String,
    query: String,
    onSearch: (androidx.compose.ui.geometry.Rect?) -> Unit,
    onFill: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val bounds = remember { BoundsHolder() }
    ListRow(
        highlighted = false,
        bounds = bounds,
        onClick = { onSearch(bounds.rect) },
        leading = {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        },
        trailing = {
            IconButton(onClick = onFill) {
                Icon(
                    Icons.Rounded.NorthWest,
                    contentDescription = stringResource(R.string.action_fill),
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    ) {
        Text(
            text = emphasizeCompletion(text, query),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Bolds the part of a suggestion the user hasn't typed yet, like browser omniboxes do. */
private fun emphasizeCompletion(suggestion: String, query: String): AnnotatedString = buildAnnotatedString {
    val typed = query.trim()
    if (typed.isNotEmpty() && suggestion.startsWith(typed, ignoreCase = true)) {
        append(suggestion.substring(0, typed.length))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(suggestion.substring(typed.length)) }
    } else {
        append(suggestion)
    }
}
