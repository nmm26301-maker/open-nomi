package ai.opennomi.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.opennomi.app.OpenNomiCloudViewModel
import ai.opennomi.app.network.VoiceEndpoint

@Composable
internal fun VoiceEndpointDialog(vm:OpenNomiCloudViewModel,onClose:()->Unit) {
    val settings=vm.endpointSettings
    var enabled by remember {mutableStateOf(settings.enabled)}
    var url by remember {mutableStateOf(settings.url)}
    var token by remember {mutableStateOf(settings.token)}
    var version by remember {mutableStateOf(settings.version)}
    var message by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=onClose,title={Text("备用语音接口")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row {Text("使用备用接口",Modifier.weight(1f));Switch(enabled,{enabled=it})}
            Text("关闭后使用原来的小智连接。可连接自建 xiaozhi-esp32-server；模型、音色与流式识别在服务器后台选择。")
            OutlinedTextField(url,{url=it},label={Text("WebSocket 地址")},placeholder={Text("wss://你的服务器/xiaozhi/v1/")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(token,{token=it},label={Text("Token（服务器无鉴权可留空）")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("小智协议版本")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { (1..3).forEach {v->FilterChip(version==v,{version=v},label={Text(v.toString())})} }
            Text("开源后端通常用版本 1；按服务器配置选择。局域网地址可用 ws://，公网使用 wss://。这个接口需要小智语音协议，普通模型 HTTP 地址不能直接填在这里。")
            if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(onClick={
        try {settings.save(VoiceEndpoint(url,token,version),enabled);vm.applyVoiceEndpoint();onClose()}
        catch(e:Exception){message=e.message ?: "接口保存失败"}
    }){Text("保存并重连")}},dismissButton={TextButton(onClick=onClose){Text("取消")}})
}
