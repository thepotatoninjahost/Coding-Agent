package com.codingagent.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * ONE JOB: Render the owner approval passphrase dialog (setup or verify).
 */
@Composable
internal fun OwnerApprovalDialog(
    setupMode: Boolean,
    typedPhrase: String,
    typedPhraseConfirm: String,
    spokenPhrase: String,
    speechChallenge: String,
    speechStatus: String,
    speechVerified: Boolean,
    error: String?,
    onTypedChange: (String) -> Unit,
    onTypedConfirmChange: (String) -> Unit,
    onSpokenChange: (String) -> Unit,
    onSpeak: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Panel,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (setupMode) "Create owner approval passphrases"
                    else "Verify owner approval",
                    color = NeonGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (setupMode)
                        "You create both passphrases. They must be different. The app stores salted verifiers, not the phrases. Speech recognition runs on-device and checks words, not speaker identity; challenge words are temporary, not stored credentials."
                    else
                        "Enter your typed passphrase and speak your separate spoken passphrase followed by the one-time challenge words. Speech recognition checks words, not speaker identity. A fresh device-authenticated signature is also required for this exact proposal.",
                    color = SoftGreen,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typedPhrase,
                    onValueChange = onTypedChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (setupMode) "Typed passphrase (create)" else "Typed passphrase", color = SoftGreen) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = fieldColors()
                )
                if (setupMode) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = typedPhraseConfirm,
                        onValueChange = onTypedConfirmChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Confirm typed passphrase", color = SoftGreen) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = fieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = spokenPhrase,
                        onValueChange = onSpokenChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Spoken passphrase (create, different from typed)", color = SoftGreen) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        colors = fieldColors()
                    )
                }
                Text(
                    "One-time challenge words to speak after the passphrase: ${speechChallenge.uppercase()}",
                    color = FluoroOrange,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onSpeak,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Panel)
                ) {
                    Text(
                        if (setupMode) "Speak and verify spoken passphrase"
                        else "Speak passphrase + challenge",
                        color = NeonGreen
                    )
                }
                Text(speechStatus, color = if (speechVerified) NeonGreen else SoftGreen, fontSize = 11.sp)
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = FluoroOrange, fontSize = 11.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = SoftGreen)
                    }
                    TextButton(onClick = onSubmit) {
                        Text(
                            if (setupMode) "Save passphrases" else "Verify and continue",
                            color = NeonGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
