package app.hush.music.polaris

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.hush.music.ui.screens.Screens

/**
 * Always visible in Hush's Scaffold.topBar, including Streaming subpages.
 * Not a child of the Radio screen: the user can return from every Hush route.
 */
@Composable
fun PolarisTopTabs(navController: NavHostController, currentRoute: String?) {
    val isRadio = currentRoute == "polaris_radio"
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            modifier = Modifier.weight(1f),
            onClick = {
                if (!isRadio) {
                    navController.navigate("polaris_radio") {
                        launchSingleTop = true
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRadio) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isRadio) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) { Text("Radio") }
        Button(
            modifier = Modifier.weight(1f),
            onClick = {
                if (isRadio) {
                    navController.navigate(Screens.ROUTE_HOME) {
                        launchSingleTop = true
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!isRadio) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (!isRadio) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) { Text("Streaming") }
    }
}
