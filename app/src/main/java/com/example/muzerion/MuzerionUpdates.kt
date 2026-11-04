package com.example.muzerion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun UpdatesContent() {
    Text(tr(R.string.msg_5709c036e1, BuildConfig.VERSION_NAME), color = MuzerionStyle.text,
        fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
    Text(tr(R.string.msg_ec416815d0), color = MuzerionStyle.text.copy(alpha = 0.6f), fontSize = 13.sp,
        modifier = Modifier.padding(bottom = 24.dp))
    val changes = listOf(
        tr(R.string.msg_2ffd5f8f5f) to tr(R.string.msg_a93ae8ffcf)
    )
    changes.forEachIndexed { index, (title, description) ->
        Text(title, color = MuzerionStyle.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp))
        Text(description, color = MuzerionStyle.text.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 23.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp))
        if (index != changes.lastIndex) HorizontalDivider(color = MuzerionStyle.text.copy(alpha = 0.1f),
            modifier = Modifier.padding(bottom = 20.dp))
    }
}
