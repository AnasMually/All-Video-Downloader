package com.anas_mugally.videodownloader.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.anas_mugally.videodownloader.domain.MediaFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AllVideoDownloaderApp(sharedUrl:String, vm:MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<MediaFormat?>(null) }
    var audioOnly by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(sharedUrl) { if (sharedUrl.isNotBlank()) vm.setUrl(sharedUrl) }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    Scaffold(topBar={CenterAlignedTopAppBar(title={Text("All Video Downloader", fontWeight=FontWeight.SemiBold)}, actions={IconButton(onClick={}){Icon(Icons.Default.Settings,"الإعدادات")}})}) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Text("نزّل الفيديو أو الصوت", style=MaterialTheme.typography.headlineMedium, fontWeight=FontWeight.Bold); Text("YouTube • Facebook • Instagram • X • TikTok", color=MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                OutlinedTextField(
                    value=state.url,
                    onValueChange=vm::setUrl,
                    modifier=Modifier.fillMaxWidth(),
                    label={Text("الصق رابط الفيديو")},
                    leadingIcon={Icon(Icons.Default.Link,null)},
                    trailingIcon={ if(state.url.isNotBlank()) IconButton(onClick={vm.setUrl(""); selected=null}){Icon(Icons.Default.Close,"مسح")} },
                    singleLine=true,
                    shape=RoundedCornerShape(20.dp)
                )
            }
            item { Button(onClick=vm::analyze,enabled=!state.loading && state.url.isNotBlank(),modifier=Modifier.fillMaxWidth().height(56.dp),shape=RoundedCornerShape(18.dp)){if(state.loading){CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp);Spacer(Modifier.width(10.dp))};Text(if(state.loading)"جارٍ تحليل الرابط…" else "عرض الجودات") } }
            state.error?.let { error -> item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Error,null);Spacer(Modifier.width(12.dp));Text(error)}} } }
            state.media?.let { media ->
                item { ElevatedCard(shape=RoundedCornerShape(24.dp)){Column{AsyncImage(model=media.thumbnailUrl,contentDescription=null,modifier=Modifier.fillMaxWidth().height(190.dp));Column(Modifier.padding(16.dp)){Text(media.title,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Bold);Text(media.extractor,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}}} }
                item { SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){SegmentedButton(selected=!audioOnly,onClick={audioOnly=false},shape=SegmentedButtonDefaults.itemShape(0,2),icon={Icon(Icons.Default.Movie,null)}){Text("فيديو")};SegmentedButton(selected=audioOnly,onClick={audioOnly=true},shape=SegmentedButtonDefaults.itemShape(1,2),icon={Icon(Icons.Default.Headphones,null)}){Text("صوت MP3")}} }
                item { Text("اختر الجودة",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold) }
                val shown = if(audioOnly) media.formats.filter{it.hasAudio && !it.hasVideo}.ifEmpty{media.formats.filter{it.hasAudio}} else media.formats.filter{it.hasVideo}
                items(shown,key={it.formatId}) { format -> FormatRow(format,selected?.formatId==format.formatId){selected=format} }
                item { Button(onClick={selected?.let{vm.download(it.formatId,audioOnly)}},enabled=selected!=null,modifier=Modifier.fillMaxWidth().height(58.dp),shape=RoundedCornerShape(18.dp)){Icon(Icons.Default.Download,null);Spacer(Modifier.width(8.dp));Text("بدء التنزيل")};Text("استخدم التطبيق فقط لتنزيل المحتوى الذي تملكه أو المسموح لك بتنزيله.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp)) }
            }
        }
    }
}

@Composable private fun FormatRow(format:MediaFormat, selected:Boolean, onClick:()->Unit) {
    val size=format.fileSize?.let{if(it>1_048_576)"%.1f MB".format(it/1_048_576.0) else "${it/1024} KB"} ?: "الحجم بعد البدء"
    Surface(onClick=onClick,shape=RoundedCornerShape(18.dp),color=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()){
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected,onClick=onClick);Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f)){Text(format.label,fontWeight=FontWeight.SemiBold);Text("${format.extension.uppercase()} • $size",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(selected)Icon(Icons.Default.CheckCircle,null,tint=MaterialTheme.colorScheme.primary)}
    }
}
