package com.sheldondesousa.uncork.ui.directory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sheldondesousa.uncork.data.knowledge.DirectoryCount
import com.sheldondesousa.uncork.data.knowledge.WineriesDirectory
import com.sheldondesousa.uncork.ui.components.AppBottomBar
import com.sheldondesousa.uncork.ui.components.AppHeader
import com.sheldondesousa.uncork.ui.theme.Hairline
import com.sheldondesousa.uncork.ui.theme.Ink
import com.sheldondesousa.uncork.ui.theme.InkSubtle
import com.sheldondesousa.uncork.ui.theme.Parchment
import com.sheldondesousa.uncork.ui.theme.Wine

/** A directory page's header: the title and a small subtext, which together say where the user is. */
internal data class DirectoryHeader(val title: String, val subtitle: String?)

/**
 * The title is always "Directory"; a breadcrumb on the page, above the list, says where the user is: "Country", then "{Country} > Select Region", then
 * "{Country} > {Region} > Winery".
 */
internal fun directoryHeader(country: String?, region: String?): DirectoryHeader = when {
    country == null -> DirectoryHeader("Directory", "Select Country")
    region == null -> DirectoryHeader("Directory", "$country > Select Region")
    else -> DirectoryHeader("Directory", "$country > $region > Winery")
}

/**
 * Browse the Wineries_Directory: countries, then the regions of a country, then the wineries of a region, each list
 * alphabetised on its own page. Back goes up one level (and leaves the directory from the country list); Home returns
 * to the main page.
 */
@Composable
fun WineryDirectoryRoute(
    loadDirectory: suspend () -> WineriesDirectory,
    onBack: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var directory by remember { mutableStateOf<WineriesDirectory?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(loadDirectory) {
        directory = runCatching { loadDirectory() }.getOrNull()
        failed = directory == null
    }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var region by rememberSaveable { mutableStateOf<String?>(null) }
    val goBack: () -> Unit = {
        when {
            region != null -> region = null
            country != null -> country = null
            else -> onBack()
        }
    }
    BackHandler(onBack = goBack)

    Column(modifier.fillMaxSize().background(Parchment).statusBarsPadding()) {
        val header = directoryHeader(country, region)
        AppHeader(title = header.title, icon = Icons.Outlined.Place)
        // The breadcrumb sits on the page itself, above the list, not in the page title.
        header.subtitle?.let {
            Text(
                it,
                color = Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).testTag("directory-breadcrumb"),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val data = directory
            when {
                data != null -> DirectoryList(data, country, region, onCountry = { country = it }, onRegion = { region = it })
                failed -> Text("The winery directory could not be loaded.", color = InkSubtle, modifier = Modifier.padding(24.dp))
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Wine, strokeWidth = 2.dp)
            }
        }
        AppBottomBar(onBack = goBack, onHome = onHome)
    }
}

@Composable
private fun DirectoryList(
    directory: WineriesDirectory,
    country: String?,
    region: String?,
    onCountry: (String) -> Unit,
    onRegion: (String) -> Unit,
) {
    when {
        country == null -> CountList(directory.countries(), "country", onClick = onCountry)
        region == null -> CountList(directory.regions(country), "region", onClick = onRegion)
        else -> {
            val wineries = directory.wineriesIn(country, region)
            LazyColumn(Modifier.fillMaxSize().testTag("directory-wineries")) {
                items(wineries) { name ->
                    Text(name, color = Ink, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp))
                    HorizontalDivider(color = Hairline)
                }
            }
        }
    }
}

@Composable
private fun CountList(rows: List<DirectoryCount>, kind: String, onClick: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("directory-$kind-list")) {
        items(rows, key = { it.name }) { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("directory-$kind-${row.name}")
                    .clickable(role = Role.Button) { onClick(row.name) }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.name, color = Ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text("${row.count}", color = InkSubtle, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = InkSubtle, modifier = Modifier.size(20.dp))
            }
            HorizontalDivider(color = Hairline)
        }
    }
}
