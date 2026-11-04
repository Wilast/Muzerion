package com.example.muzerion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun LanguageOptions() {
    val context=LocalContext.current
    listOf("en" to "English","ru" to "Русский").forEach {(code,label)->
        Row(Modifier.fillMaxWidth().clickable {AppLanguage.select(context,code)}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
            RadioButton(selected=AppLanguage.code==code,onClick={AppLanguage.select(context,code)})
            Text(label,modifier=Modifier.padding(start=12.dp),color=MuzerionStyle.text)
        }
    }
}
