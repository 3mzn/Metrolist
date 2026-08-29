package com.metrolist.music.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.R
import kotlinx.coroutines.delay

private data class Slide(
    val background: List<Color>,
    val textResIds: List<Int>,
    val delays: List<Long> = emptyList(),
)

@Composable
fun OnboardingScreen(
    initialOnboardingCompleted: Boolean,
    onComplete: () -> Unit,
) {
    val slides = remember {
        listOf(
            Slide(
                background = listOf(Color(0xFF1A0A2E), Color(0xFF2D1B4E)),
                textResIds = listOf(R.string.onboarding_slide1),
                delays = listOf(0L),
            ),
            Slide(
                background = listOf(Color(0xFF2E1A1A), Color(0xFF4E2D2D)),
                textResIds = listOf(R.string.onboarding_slide2),
                delays = listOf(0L),
            ),
            Slide(
                background = listOf(Color(0xFF0A2E2E), Color(0xFF1B4E4E)),
                textResIds = listOf(R.string.onboarding_slide3),
                delays = listOf(0L),
            ),
            Slide(
                background = listOf(Color(0xFF2E2A0A), Color(0xFF4E451B)),
                textResIds = listOf(R.string.onboarding_slide4),
                delays = listOf(0L),
            ),
            Slide(
                background = listOf(Color(0xFF0A1A2E), Color(0xFF1B2D4E)),
                textResIds = listOf(
                    R.string.onboarding_slide5a,
                    R.string.onboarding_slide5b,
                    R.string.onboarding_slide5c,
                ),
                delays = listOf(0L, 1000L, 3000L),
            ),
            Slide(
                background = listOf(Color(0xFF0E2E0A), Color(0xFF1E4E1B)),
                textResIds = listOf(
                    R.string.onboarding_slide6a,
                    R.string.onboarding_slide6b,
                    R.string.onboarding_slide6c,
                ),
                delays = listOf(0L, 1000L, 1000L),
            ),
            Slide(
                background = listOf(Color(0xFF2E0A2A), Color(0xFF4E1B45)),
                textResIds = listOf(R.string.onboarding_slide7),
                delays = listOf(0L),
            ),
        )
    }

    if (initialOnboardingCompleted) {
        onComplete()
        return
    }

    var currentSlide by remember { mutableIntStateOf(0) }
    var buttonVisible by remember { mutableStateOf(false) }
    val slide = slides[currentSlide]
    val isLast = currentSlide == slides.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = slide.background,
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                )
            ),
    ) {
        SequentialTexts(
            slide = slide,
            onAllComplete = { buttonVisible = true },
        )

        AnimatedVisibility(
            visible = buttonVisible,
            enter = fadeIn(animationSpec = tween(800, easing = EaseOutCubic)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
        ) {
            Button(
                onClick = {
                    if (isLast) {
                        onComplete()
                    } else {
                        buttonVisible = false
                        currentSlide++
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(if (isLast) R.string.onboarding_done else R.string.onboarding_next),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SequentialTexts(
    slide: Slide,
    onAllComplete: () -> Unit,
) {
    val completedCount = remember { mutableIntStateOf(0) }

    LaunchedEffect(slide) {
        completedCount.intValue = 0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        slide.textResIds.forEachIndexed { index, resId ->
            val gapDelay = slide.delays.getOrElse(index) { 0L }
            var startTyping by remember { mutableStateOf(false) }

            LaunchedEffect(completedCount.intValue, slide) {
                if (index == 0) {
                    startTyping = true
                } else if (completedCount.intValue == index) {
                    delay(gapDelay)
                    startTyping = true
                }
            }

            TypewriterText(
                text = stringResource(resId),
                startTyping = startTyping,
                isBottomText = index == 2 && slide.textResIds.size == 3,
                onTypingComplete = {
                    completedCount.intValue++
                    if (completedCount.intValue >= slide.textResIds.size) {
                        onAllComplete()
                    }
                },
            )
        }
    }
}

@Composable
private fun TypewriterText(
    text: String,
    startTyping: Boolean,
    modifier: Modifier = Modifier,
    isBottomText: Boolean = false,
    onTypingComplete: () -> Unit = {},
) {
    var displayedText by remember { mutableStateOf("") }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(text, startTyping) {
        if (!startTyping) return@LaunchedEffect
        displayedText = ""
        alpha.snapTo(0f)
        alpha.animateTo(1f, animationSpec = tween(600, easing = EaseOutCubic))
        for (ch in text) {
            displayedText += ch
            delay(28L)
        }
        onTypingComplete()
    }

    Text(
        text = displayedText,
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        style = TextStyle(
            shadow = Shadow(
                color = Color.White.copy(alpha = 0.25f),
                offset = Offset(0f, 0f),
                blurRadius = 24f,
            ),
        ),
        modifier = modifier
            .alpha(alpha.value)
            .fillMaxWidth()
            .then(if (isBottomText) Modifier.padding(top = 16.dp) else Modifier),
    )
}
