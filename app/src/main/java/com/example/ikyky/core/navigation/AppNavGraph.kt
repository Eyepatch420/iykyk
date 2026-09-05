package com.example.ikyky.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ikyky.features.collage.presentation.screen.CollageScreen
import com.example.ikyky.features.people.presentation.screen.PeopleScreen
import com.example.ikyky.features.people.presentation.screen.PersonDetailScreen
import com.example.ikyky.features.processing.presentation.screen.ProcessingScreen
import com.example.ikyky.features.result.presentation.screen.ResultScreen
import com.example.ikyky.features.video_selection.presentation.screen.VideoSelectionScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Single-activity Compose navigation for the linear pipeline flow.
 *
 * A fresh `sessionId` (UUID) is minted when the user starts processing and is
 * threaded through the downstream screens so each reads its slice of the
 * in-memory session results.
 */
@Composable
fun AppNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = AppDestinations.START,
        modifier = modifier,
    ) {
        composable(AppDestinations.VIDEO_SELECTION) {
            VideoSelectionScreen(
                onProceed = { uriString ->
                    val encoded = URLEncoder.encode(uriString, StandardCharsets.UTF_8.name())
                    val session = UUID.randomUUID().toString()
                    navController.navigate(
                        "${AppDestinations.PROCESSING}?uri=$encoded&session=$session"
                    )
                },
            )
        }

        composable("${AppDestinations.PROCESSING}?uri={uri}&session={session}") { backStackEntry ->
            val uri = backStackEntry.arguments?.getString("uri")
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
                .orEmpty()
            val session = backStackEntry.arguments?.getString("session") ?: "default"
            ProcessingScreen(
                sessionId = session,
                uriString = uri,
                onFinished = { finishedSession ->
                    navController.navigate("${AppDestinations.PEOPLE}?session=$finishedSession") {
                        popUpTo(AppDestinations.VIDEO_SELECTION)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable("${AppDestinations.PEOPLE}?session={session}") { backStackEntry ->
            val session = backStackEntry.arguments?.getString("session") ?: "default"
            PeopleScreen(
                sessionId = session,
                onOpenPerson = { personId ->
                    navController.navigate(
                        "${AppDestinations.PERSON_DETAIL}?session=$session&personId=$personId"
                    )
                },
                onMakeCollage = {
                    navController.navigate("${AppDestinations.COLLAGE}?session=$session")
                },
            )
        }

        composable(
            "${AppDestinations.PERSON_DETAIL}?session={session}&personId={personId}"
        ) { backStackEntry ->
            val session = backStackEntry.arguments?.getString("session") ?: "default"
            val personId = backStackEntry.arguments?.getString("personId") ?: ""
            PersonDetailScreen(
                sessionId = session,
                personId = personId,
                onBack = { navController.popBackStack() },
            )
        }

        composable("${AppDestinations.COLLAGE}?session={session}") { backStackEntry ->
            val session = backStackEntry.arguments?.getString("session") ?: "default"
            CollageScreen(
                sessionId = session,
                onDone = { navController.navigate("${AppDestinations.RESULT}?session=$session") },
            )
        }

        composable("${AppDestinations.RESULT}?session={session}") { backStackEntry ->
            val session = backStackEntry.arguments?.getString("session") ?: "default"
            ResultScreen(sessionId = session)
        }
    }
}
