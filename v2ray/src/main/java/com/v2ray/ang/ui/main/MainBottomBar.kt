package com.v2ray.ang.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.v2ray.ang.ui.compose.AppDivider

/** Connection diagnostics only; enablement belongs to the note settings. */
@Composable
fun MainBottomBar(displayText: String, onAction: (MainAction) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()
        .clickable { onAction(MainAction.TestCurrentServer) }) {
        AppDivider()
        Text(displayText, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp))
    }
}
