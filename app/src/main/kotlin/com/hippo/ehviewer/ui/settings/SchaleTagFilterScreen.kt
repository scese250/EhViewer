package com.hippo.ehviewer.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.launch
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.schale.SchaleEngine
import com.hippo.ehviewer.client.schale.SchaleTagInfo
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

private val HELP_TEXT = """
    Toca una etiqueta para cambiar su filtro:
    · primer toque: lista blanca, solo se ven las galerías que la llevan
    · segundo toque: lista negra, se ocultan las galerías que la llevan
    · tercer toque: sin filtro

    Los filtros valen para esta sesión de la app, no para tu cuenta: la web tiene los suyos y no se
    sincronizan. Se aplican a las listas, a las búsquedas y a los favoritos, y el servidor los aplica
    mientras arma la página, así que la lista queda ya filtrada.
""".trimIndent()

/** Which list a tag's filter sits in. The site has no other filter kinds. */
private enum class TagFilterState { Off, Whitelist, Blacklist }

/** One selectable tag: the term a filter stores and how the list shows it. */
private data class SchaleTagChoice(
    val term: String,
    val name: String,
    val namespace: String?,
    val count: Int?,
)

/** A tag of the site, turned into the term a filter stores for it. */
private fun SchaleTagInfo.toChoice(): SchaleTagChoice {
    val term = SchaleEngine.tagTerm(namespace, name)
    return SchaleTagChoice(term, name, term.namespacePrefix(), count)
}

/**
 * A term that is filtered but missing from the site's tag list, either because it is not a plain tag
 * (an artist) or because it was typed by hand. It still has to be listed, or it could never be
 * switched off from here.
 */
private fun termChoice(term: String): SchaleTagChoice {
    val prefix = term.substringBefore(':', missingDelimiterValue = "")
    val name = if (prefix.isEmpty()) term else term.substringAfter(':')
    return SchaleTagChoice(term, name, term.namespacePrefix(), null)
}

/** The namespace to show next to a tag, or null for the plain tags (`tag:…`). */
private fun String.namespacePrefix(): String? {
    val colon = indexOf(':')
    if (colon <= 0) return null
    return substring(0, colon).takeIf { it != "tag" }
}

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.SchaleTagFilterScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val whitelist by Settings.schaleTagWhitelist.collectAsState()
    val blacklist by Settings.schaleTagBlacklist.collectAsState()
    val enabled by Settings.schaleTagFilters.collectAsState()
    val animateItems by Settings.animateItems.collectAsState()
    val search = rememberTextFieldState()
    // The tag list is the same for every reader and the endpoint does not paginate, so one fetch per
    // visit is enough. A failure only costs the suggestions: a term can still be typed by hand.
    var siteTags by remember { mutableStateOf<List<SchaleTagInfo>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { SchaleEngine.getSchaleTags() }
            .onSuccess { siteTags = it }
            .onFailure { failure = it.message ?: it::class.simpleName }
    }

    fun setState(term: String, state: TagFilterState) {
        val required = Settings.schaleTagWhitelist.value
        val excluded = Settings.schaleTagBlacklist.value
        Settings.schaleTagWhitelist.value = if (state == TagFilterState.Whitelist) required + term else required - term
        Settings.schaleTagBlacklist.value = if (state == TagFilterState.Blacklist) excluded + term else excluded - term
    }

    fun cycle(term: String) {
        val current = when {
            term in Settings.schaleTagWhitelist.value -> TagFilterState.Whitelist
            term in Settings.schaleTagBlacklist.value -> TagFilterState.Blacklist
            else -> TagFilterState.Off
        }
        // First touch whitelists a tag, the second blacklists it, the third clears it — the cycle the
        // site's own filter list uses.
        val next = when (current) {
            TagFilterState.Off -> TagFilterState.Whitelist
            TagFilterState.Whitelist -> TagFilterState.Blacklist
            TagFilterState.Blacklist -> TagFilterState.Off
        }
        setState(term, next)
    }

    val needle = search.text.toString().trim().lowercase()
    val active = whitelist + blacklist
    val whitelistChoices = whitelist.sorted().map(::termChoice)
    val blacklistChoices = blacklist.sorted().map(::termChoice)
    val matches = (siteTags ?: emptyList())
        .map { it.toChoice() }
        .filter { it.term !in active }
        .filter { needle.isEmpty() || it.name.lowercase().contains(needle) || it.term.lowercase().contains(needle) }
    // What the search box holds when it is not an existing tag: a namespace prefix is kept as typed,
    // anything else is a plain tag. It is how an artist or a brand new tag gets filtered at all.
    val manual = search.text.toString().trim().takeIf { it.isNotEmpty() }
        ?.let { if (it.contains(':')) it else "tag:$it" }
        ?.takeIf { it !in active }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Filtros de etiquetas") },
                navigationIcon = { NavigationIcon() },
                actions = {
                    IconButton(
                        onClick = {
                            launch {
                                awaitConfirmationOrCancel(title = R.string.filter, confirmText = R.string.delete) {
                                    Text(text = "Se quitarán todos los filtros de etiquetas de Schale Network.")
                                }
                                Settings.schaleTagWhitelist.value = emptySet()
                                Settings.schaleTagBlacklist.value = emptySet()
                            }
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Quitar todos los filtros")
                    }
                    IconButton(
                        onClick = {
                            launch {
                                awaitConfirmationOrCancel(title = R.string.filter, showCancelButton = false) {
                                    Text(text = HELP_TEXT)
                                }
                            }
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Default.Help, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = paddingValues,
        ) {
            item(key = "header") {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        state = search,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text(text = "Buscar una etiqueta") },
                        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Aplicar los filtros a las listas")
                            Text(
                                text = if (enabled) {
                                    "La lista blanca exige la etiqueta; la lista negra la oculta."
                                } else {
                                    "Desactivados: las listas vuelven a mostrar todo."
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = { Settings.schaleTagFilters.value = it })
                    }
                }
            }

            if (whitelistChoices.isNotEmpty()) {
                item(key = "white-title") { SectionTitle("Lista blanca (${whitelistChoices.size})") }
                items(whitelistChoices, key = { "white:${it.term}" }) { choice ->
                    TagRow(
                        modifier = Modifier.thenIf(animateItems) { animateItem() },
                        choice = choice,
                        state = TagFilterState.Whitelist,
                        onClick = { cycle(choice.term) },
                        onRemove = { setState(choice.term, TagFilterState.Off) },
                    )
                }
            }
            if (blacklistChoices.isNotEmpty()) {
                item(key = "black-title") { SectionTitle("Lista negra (${blacklistChoices.size})") }
                items(blacklistChoices, key = { "black:${it.term}" }) { choice ->
                    TagRow(
                        modifier = Modifier.thenIf(animateItems) { animateItem() },
                        choice = choice,
                        state = TagFilterState.Blacklist,
                        onClick = { cycle(choice.term) },
                        onRemove = { setState(choice.term, TagFilterState.Off) },
                    )
                }
            }

            item(key = "all-title") { SectionTitle("Todas las etiquetas (${matches.size})") }
            manual?.let { term ->
                item(key = "manual") {
                    TagRow(
                        modifier = Modifier.thenIf(animateItems) { animateItem() },
                        choice = termChoice(term),
                        state = TagFilterState.Off,
                        onClick = { cycle(term) },
                    )
                }
            }

            if (siteTags == null) {
                // Nothing to offer until the site's own list arrives, and nothing to filter out of
                // either: the box above already covers a tag typed by hand.
                item(key = "unavailable") {
                    if (failure == null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Text(
                            text = "No se pudo leer la lista de etiquetas del sitio ($failure). " +
                                "Escribe el nombre en el buscador para filtrarlo igualmente.",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else if (matches.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "Ninguna etiqueta coincide con la búsqueda.",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(matches, key = { "all:${it.term}" }) { choice ->
                    TagRow(
                        modifier = Modifier.thenIf(animateItems) { animateItem() },
                        choice = choice,
                        state = TagFilterState.Off,
                        onClick = { cycle(choice.term) },
                    )
                }
            }
        }
    }
}

/**
 * One tag. Tapping it advances the filter (whitelist, then blacklist, then off), and the trailing
 * button clears it in one step instead of walking the cycle.
 */
@Composable
private fun TagRow(
    choice: SchaleTagChoice,
    state: TagFilterState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (state) {
                TagFilterState.Whitelist -> Icons.Default.Check
                TagFilterState.Blacklist -> Icons.Default.Block
                TagFilterState.Off -> Icons.Default.Add
            },
            contentDescription = null,
            tint = when (state) {
                TagFilterState.Whitelist -> MaterialTheme.colorScheme.primary
                TagFilterState.Blacklist -> MaterialTheme.colorScheme.error
                TagFilterState.Off -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(text = choice.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            choice.namespace?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        choice.count?.let {
            Text(
                text = "$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, shapes = IconButtonDefaults.shapes()) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Quitar el filtro")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.titleMedium,
    )
}
