package com.nrknithin.fintrack.ui.screens.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import com.nrknithin.fintrack.ui.effects.overScrollVertical
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
// TextFieldDefaults already imported above
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nrknithin.fintrack.R
import androidx.compose.foundation.isSystemInDarkTheme
import com.nrknithin.fintrack.ui.theme.Dimensions
import com.nrknithin.fintrack.ui.theme.Spacing
import com.nrknithin.fintrack.ui.theme.income

@Composable
fun OnBoardingScreen(
    onOnboardingComplete: () -> Unit,
    viewModel: OnBoardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val stepOrder = remember {
        OnBoardingStep.entries.toList()
    }
    Scaffold(
        bottomBar = {
            OnBoardingBottomBar(
                uiState = uiState,
                onBack = { viewModel.goToPreviousStep() },
                onNext = {
                    when (uiState.currentStep) {
                        OnBoardingStep.WELCOME -> viewModel.goToNextStep()
                        OnBoardingStep.PROFILE -> viewModel.goToNextStep()
                        OnBoardingStep.PERMISSIONS -> viewModel.goToNextStep()
                        // Terminal step: Continue finishes onboarding and leaves the
                        // scan running in WorkManager.
                        OnBoardingStep.SMS_SCAN ->
                            viewModel.completeOnboarding(onOnboardingComplete)
                    }
                },
                onSkip = {
                    when (uiState.currentStep) {
                        OnBoardingStep.PERMISSIONS -> {
                            viewModel.skipSmsPermission()
                            viewModel.goToNextStep()
                        }
                        OnBoardingStep.SMS_SCAN ->
                            viewModel.completeOnboarding(onOnboardingComplete)
                        else -> viewModel.goToNextStep()
                    }
                },
                onStartScan = { viewModel.startSmsScan() }
            )
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = uiState.currentStep,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            transitionSpec = {
                val targetIndex = stepOrder.indexOf(targetState)
                val initialIndex = stepOrder.indexOf(initialState)
                if (targetIndex > initialIndex) {
                    slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                } else {
                    slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                }
            },
            label = "onboarding_step"
        ) { step ->
            when (step) {
                OnBoardingStep.WELCOME -> WelcomeStep()
                OnBoardingStep.PROFILE -> ProfileStep(
                    uiState = uiState,
                    viewModel = viewModel
                )
                OnBoardingStep.PERMISSIONS -> PermissionsStep(
                    uiState = uiState,
                    onPermissionResult = { viewModel.onSmsPermissionResult(it) }
                )
                OnBoardingStep.SMS_SCAN -> SmsScanStep(uiState = uiState)
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = "FinTrack",
            modifier = Modifier
                .size(HERO_ICON_SIZE)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.height(Spacing.xl))

        Text(
            text = "Welcome to FinTrack",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        Text(
            text = "Your AI-powered expense tracker that automatically detects transactions from SMS messages.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text(
                    text = "What you'll set up:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = "1. Your profile\n2. SMS permissions for auto-detection\n3. Initial transaction scan\n4. Your main bank account",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileStep(
    uiState: OnBoardingUiState,
    viewModel: OnBoardingViewModel
) {
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.selectProfileImage(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(Spacing.xl))

        Text(
            // The screen must not ask a question it will not let you answer: when the name
            // comes from the signed-in Google account the field below is locked.
            text = "Set up your profile",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        TextField(
            value = uiState.userName,
            onValueChange = { if (!uiState.isNameLocked) viewModel.updateUserName(it) },
            // Both flags: enabled = false greys it and blocks focus, readOnly stops any
            // programmatic edit path.
            enabled = !uiState.isNameLocked,
            readOnly = uiState.isNameLocked,
            label = { Text("Your name") },
            supportingText = if (uiState.isNameLocked) {
                { Text("From your Google account") }
            } else null,
            // The one unambiguous cue that the field is locked on purpose. Keeping the
            // text at full contrast (below) fixes the "looks broken" problem but removes
            // every signal that it is uneditable — so a tap that raises no keyboard and
            // shows no cursor has nothing to explain it. A lock glyph carries that at
            // full contrast in both themes, where dimming would not.
            trailingIcon = if (uiState.isNameLocked) {
                {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Name comes from your Google account and cannot be edited"
                    )
                }
            } else null,
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f),
                // Not optional: M3 drops disabled text to 38% alpha, which makes the user's
                // own name read as broken rather than deliberately locked, in both themes.
                // The label and supporting text stay at full-opacity onSurfaceVariant —
                // dimming an already-muted role stacks two reductions (docs/design.md).
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledIndicatorColor = Color.Transparent,
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )

        Spacer(modifier = Modifier.height(Spacing.xl))

        Text(
            text = "Choose an avatar",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(viewModel.avatarDrawables) { index, drawableRes ->
                val isSelected = uiState.profileImageUri == null && uiState.selectedAvatarIndex == index
                Box(
                    modifier = Modifier
                        .size(OPTION_TILE_SIZE)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .then(
                            if (isSelected) Modifier.border(
                                SELECTION_RING_WIDTH,
                                MaterialTheme.colorScheme.primary,
                                CircleShape
                            ) else Modifier
                        )
                        .clickable { viewModel.selectAvatar(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = drawableRes),
                        contentDescription = "Avatar ${index + 1}",
                        modifier = Modifier.size(Dimensions.Icon.avatar),
                        colorFilter = ColorFilter.tint(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            item {
                // Gallery picker option
                Box(
                    modifier = Modifier
                        .size(OPTION_TILE_SIZE)
                        .clip(CircleShape)
                        .background(
                            if (uiState.profileImageUri != null) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .then(
                            if (uiState.profileImageUri != null) Modifier.border(
                                SELECTION_RING_WIDTH,
                                MaterialTheme.colorScheme.primary,
                                CircleShape
                            ) else Modifier
                        )
                        .clickable { imagePicker.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.profileImageUri != null) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Photo selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Dimensions.Icon.large)
                        )
                    } else {
                        Text(
                            text = "+",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        Text(
            text = "Pick a background color",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth()
        ) {
            viewModel.backgroundColors.forEachIndexed { index, colorInt ->
                val isSelected = uiState.selectedBackgroundColor == index
                Box(
                    modifier = Modifier
                        .size(Dimensions.Icon.avatar)
                        .clip(CircleShape)
                        .background(Color(colorInt))
                        .then(
                            if (isSelected) Modifier.border(
                                SELECTION_RING_WIDTH,
                                MaterialTheme.colorScheme.onSurface,
                                CircleShape
                            ) else Modifier
                        )
                        .clickable { viewModel.selectBackgroundColor(index) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(Dimensions.Icon.medium)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionsStep(
    uiState: OnBoardingUiState,
    onPermissionResult: (Boolean) -> Unit
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readSmsGranted = permissions[Manifest.permission.READ_SMS] == true
        onPermissionResult(readSmsGranted)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.MailOutline,
            contentDescription = null,
            modifier = Modifier.size(HERO_ICON_SIZE_SMALL),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        Text(
            text = "Enable Automatic Detection",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        Text(
            text = "FinTrack can automatically detect and categorize your bank transactions from SMS messages.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text(
                    text = "Your Privacy Matters",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = "\u2022 Only transaction messages are processed\n" +
                            "\u2022 All data stays on your device\n" +
                            "\u2022 No personal messages are read\n" +
                            "\u2022 You can revoke access anytime in Settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        if (uiState.smsPermissionGranted) {
            val incomeColor = MaterialTheme.colorScheme.income
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = incomeColor.copy(alpha = if (isSystemInDarkTheme()) 0.15f else 0.12f)
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = incomeColor
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        text = "Permissions granted! Tap Continue to proceed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = incomeColor
                    )
                }
            }
        } else {
            Button(
                onClick = {
                    val permissions = mutableListOf(
                        Manifest.permission.READ_SMS,
                        Manifest.permission.RECEIVE_SMS
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissionLauncher.launch(permissions.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable Permissions")
            }
        }
    }
}

@Composable
private fun SmsScanStep(uiState: OnBoardingUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (uiState.isScanning) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimensions.Icon.emptyStateContainer)
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            Text(
                text = "Scanning your messages...",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(Spacing.md))

            if (uiState.scanTotal > 0) {
                val progress = uiState.scanProcessed.toFloat() / uiState.scanTotal.toFloat()
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = "${uiState.scanProcessed} / ${uiState.scanTotal} messages processed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.scanParsed > 0) {
                    Text(
                        text = "${uiState.scanParsed} transactions found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (uiState.scanEstimatedRemaining > 0) {
                    val seconds = uiState.scanEstimatedRemaining / 1000
                    Text(
                        text = "~${seconds}s remaining",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.lg))

                // The point of the whole change: without this the progress bar still reads
                // as something to wait for, and the Finish button looks like it would
                // cancel the scan.
                Text(
                    text = "You can finish now — this keeps running in the background, " +
                        "and transactions will keep appearing on your home screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = "Preparing scan...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (uiState.scanCompleted) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.Icon.emptyStateContainer),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            Text(
                text = "Scan Complete!",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(Spacing.md))

            if (uiState.scanSaved > 0) {
                Text(
                    text = "${uiState.scanSaved} transactions saved from ${uiState.scanTotal} messages",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "No transactions found. You can add them manually later.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Not started yet
            Icon(
                imageVector = Icons.Filled.MailOutline,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.Icon.emptyStateContainer),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            Text(
                text = "Scan Your Messages",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(Spacing.md))

            Text(
                text = "We'll scan your SMS messages to find bank transactions and set up your accounts automatically.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StepIndicator(
    currentStep: OnBoardingStep,
    modifier: Modifier = Modifier
) {
    val steps = OnBoardingStep.entries
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, step ->
            val isActive = step == currentStep
            val isPast = index < steps.indexOf(currentStep)
            Box(
                modifier = Modifier
                    .padding(horizontal = Spacing.xs)
                    .size(if (isActive) STEP_DOT_ACTIVE else STEP_DOT_INACTIVE)
                    .clip(CircleShape)
                    .background(
                        when {
                            isActive -> MaterialTheme.colorScheme.primary
                            isPast -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        }
                    )
            )
        }
    }
}

@Composable
private fun OnBoardingBottomBar(
    uiState: OnBoardingUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onStartScan: () -> Unit
) {
    val isFirstStep = uiState.currentStep == OnBoardingStep.WELCOME
    // Back stays available during the scan. It was disabled because leaving the step used
    // to abandon a scan the next step depended on; neither is true now.
    val canGoBack = !isFirstStep && !uiState.isCompleting

    // Keep the controls above the system navigation bar under edge-to-edge (#563):
    // the Scaffold's bottomBar slot doesn't apply navigation-bar insets on its own,
    // so without this the gesture/3-button nav bar overlaps the Next button.
    Column(modifier = Modifier.navigationBarsPadding()) {
        StepIndicator(
            currentStep = uiState.currentStep,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.sm)
        )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        if (canGoBack) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }

        // Skip / CTA button area
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (uiState.currentStep) {
                OnBoardingStep.WELCOME -> {
                    Button(onClick = onNext) {
                        Text("Get Started")
                    }
                }

                OnBoardingStep.PROFILE -> {
                    Button(
                        onClick = onNext,
                        enabled = uiState.userName.isNotBlank()
                    ) {
                        Text("Save & Continue")
                    }
                }

                OnBoardingStep.PERMISSIONS -> {
                    if (!uiState.smsPermissionGranted) {
                        TextButton(onClick = onSkip) {
                            Text("Skip")
                        }
                    }
                    if (uiState.smsPermissionGranted) {
                        Button(onClick = onNext) {
                            Text("Continue")
                        }
                    }
                }

                // The scan never gates this step. It runs in WorkManager under unique
                // work, so it survives leaving onboarding, the app being backgrounded and
                // the process being killed — waiting for it bought nothing. On a mailbox
                // of ~8,900 messages it is minutes of staring at a progress bar.
                OnBoardingStep.SMS_SCAN -> {
                    if (!uiState.isScanning && !uiState.scanCompleted) {
                        TextButton(onClick = onSkip) {
                            Text("Skip")
                        }
                        Button(onClick = onStartScan) {
                            Text("Start Scanning")
                        }
                    } else {
                        Button(onClick = onNext) {
                            if (uiState.isCompleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(Dimensions.Icon.medium),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                // "Finish" and not "Continue": this is the last step now,
                                // and while scanning it must not imply the scan is done.
                                Text("Finish")
                            }
                        }
                    }
                }

            }
        }
    }
    }
}

// Onboarding-only geometry. These are intentionally larger than any in-app
// token: full-screen illustrative moments, not list chrome.
private val HERO_ICON_SIZE = 100.dp
private val HERO_ICON_SIZE_SMALL = 80.dp
private val OPTION_TILE_SIZE = 72.dp
private val SELECTION_RING_WIDTH = 3.dp
private val STEP_DOT_ACTIVE = Spacing.sm
private val STEP_DOT_INACTIVE = 6.dp
