package ke.tafiti.pumppay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The launch screen: the mark and the name on black, for a moment.
 *
 * This replaced an intro video. A video is a lot of weight for something that
 * is on screen for two seconds, it cannot be recoloured without re-rendering
 * it, and it needed four separate escape hatches because a decoder that stalls
 * would otherwise trap the user on a black screen. A still has none of those
 * failure modes, so it only needs two ways out: it times itself out, and a tap
 * skips it. onDone is guarded so whichever fires first wins.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val done = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val latest by rememberUpdatedState(onDone)

    val finish = remember {
        {
            if (done.compareAndSet(false, true)) latest()
        }
    }

    LaunchedEffect(Unit) {
        delay(1500)
        finish()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(C.Bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { finish() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(96.dp)
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "PumpPay",
                color = C.Text,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
