package com.anas_mugally.videodownloader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF5B4FC4),secondary=androidx.compose.ui.graphics.Color(0xFF625B71),tertiary=androidx.compose.ui.graphics.Color(0xFF7D5260))
private val Dark = darkColorScheme(primary=androidx.compose.ui.graphics.Color(0xFFC8BFFF),secondary=androidx.compose.ui.graphics.Color(0xFFCBC2DB),tertiary=androidx.compose.ui.graphics.Color(0xFFEFB8C8))
@Composable fun DownloaderTheme(content:@Composable()->Unit){val dark=isSystemInDarkTheme();val context=LocalContext.current;val colors=if(Build.VERSION.SDK_INT>=31){if(dark)dynamicDarkColorScheme(context)else dynamicLightColorScheme(context)}else if(dark)Dark else Light;MaterialTheme(colorScheme=colors,typography=Typography(),content=content)}
