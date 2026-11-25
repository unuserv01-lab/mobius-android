package com.mobius.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * v7.9-ui — generic approval dialog (standalone, reusable).
 *
 * Same look as the other MOBIUS confirmations (MainActivity: send / plan /
 * step / attach dialogs): Material 3 AlertDialog on the app's dark theme,
 * gradient [GradientButton] for confirm, plain [TextButton] for cancel.
 * Dismissing the dialog (back press / tap outside) counts as cancel, same
 * as the existing dialogs.
 *
 * Used for the Google-account logout confirmation
 * ([GoogleAccountSection]) and available for any other approval flow —
 * the caller supplies the texts, this file owns no decision logic.
 */
@Composable
fun GoogleApprovalDialog(
    title: String,
    details: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmLabel: String,
    cancelLabel: String,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(details) },
        confirmButton = {
            GradientButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(cancelLabel) }
        },
    )
}
