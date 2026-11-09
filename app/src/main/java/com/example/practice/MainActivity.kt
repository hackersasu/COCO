package com.example.practice

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * COCO 앱 자체의 설정 화면 (PRD 7.8). 다른 앱 위에는 아무것도 띄우지 않으니 HC-0의 예외예요.
 * - 접근성 서비스 켜기, 마이크 권한
 * - AI 상태(GPT 키가 있는지)
 * - 테스트 입력칸: 음성 대신 문장을 넣어 순위 요청을 시험(개발용)
 * 데모 설정(DEMO_TIME, 안내 단계)은 M3~M4에서 추가해요.
 */
class MainActivity : ComponentActivity() {

    private val serviceOn = mutableStateOf(false)
    private val micOn = mutableStateOf(false)
    private val usesGpt = mutableStateOf(false)
    private val lastResult = mutableStateOf<String?>(null)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micOn.value = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = CocoBlue)) {
                SettingsScreen(
                    serviceOn = serviceOn.value,
                    micOn = micOn.value,
                    usesGpt = usesGpt.value,
                    lastResult = lastResult.value,
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onAskMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                    onRankTest = ::rankTest,
                    onM1Test = ::m1Test,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 설정에서 돌아오거나 홈에서 다시 열 때마다 상태를 새로 확인해요.
        serviceOn.value = isCocoServiceEnabled()
        micOn.value = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val service = CocoAccessibilityService.instance
        usesGpt.value = service?.usesGpt ?: (!Config.DEMO_MODE && BuildConfig.OPENAI_API_KEY.isNotBlank())
        lastResult.value = service?.let { s ->
            val said = s.lastUtterance ?: return@let null
            "\"$said\"\n${s.lastRank?.summary() ?: "(순위 계산 중)"}"
        }
    }

    /** 개발용: 말 대신 문장을 넣어 순위 → 안내까지 시험해요. */
    private fun rankTest(sentence: String) {
        val service = requireService() ?: return
        if (sentence.isBlank()) return
        service.handleUtterance(sentence.trim())
        goHome()
    }

    /** 개발용(M1): AI 없이 '유튜브에서 리센느 검색'으로 바로 시작해요. */
    private fun m1Test() {
        val service = requireService() ?: return
        service.startGuide()
        goHome()
    }

    private fun requireService(): CocoAccessibilityService? {
        val service = CocoAccessibilityService.instance
        if (service == null) Toast.makeText(this, "먼저 접근성 서비스를 켜 주세요", Toast.LENGTH_SHORT).show()
        return service
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun isCocoServiceEnabled(): Boolean {
        val me = ComponentName(this, CocoAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

private val CocoBlue = Color(Config.DOT_COLOR)
private val Ink = Color(0xFF0F1725)
private val Muted = Color(0xFF5B6678)
private val Line = Color(0xFFDCE2EB)
private val Bg = Color(0xFFF6F8FB)
private val Ok = Color(0xFF1E7A4F)

@Composable
private fun SettingsScreen(
    serviceOn: Boolean,
    micOn: Boolean,
    usesGpt: Boolean,
    lastResult: String?,
    onOpenAccessibility: () -> Unit,
    onAskMic: () -> Unit,
    onRankTest: (String) -> Unit,
    onM1Test: () -> Unit,
) {
    var sentence by remember { mutableStateOf("리센느가 뭐야?") }
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).background(CocoBlue, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text("COCO", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        Text(
            "You tap. COCO points the way.\n코코는 다른 앱 위에 파란 점 하나로만 나타나요. 이 화면은 코코의 설정 화면이에요.",
            fontSize = 15.sp,
            color = Muted,
            lineHeight = 22.sp,
        )

        Card {
            TitleRow("1. 접근성 서비스", if (serviceOn) "켜짐" else "꺼짐", serviceOn)
            Body(
                if (serviceOn) {
                    "준비됐어요."
                } else {
                    "아래 버튼 → '다운로드한 앱'(Downloaded apps) → COCO → 'COCO 바로가기'(COCO shortcut) 스위치 켜기 → 허용. 접근성 버튼을 쓰는 앱이라 바로가기를 켜면 COCO도 함께 켜져요."
                },
            )
            PrimaryButton("접근성 설정 열기", onOpenAccessibility)
        }

        Card {
            TitleRow("2. 마이크", if (micOn) "허용됨" else "필요", micOn)
            Body("\"코코야\" 하고 부른 뒤 하는 말을 들으려면 필요해요. 들은 말만 AI에 보내고, 화면 내용은 보내지 않아요.")
            if (!micOn) PrimaryButton("마이크 허용하기", onAskMic)
        }

        Card {
            TitleRow("3. AI", if (usesGpt) "GPT" else "데모 순위표", usesGpt)
            Body(
                if (usesGpt) {
                    "${Config.OPENAI_MODEL}가 말을 듣고 기능 순위를 매겨요. 실패하면 데모 순위표로 대신해요."
                } else {
                    "GPT 키가 없어서 미리 적어 둔 문장(demo_rankings.json)만 알아들어요. local.properties의 OPENAI_API_KEY= 뒤에 키를 넣고 다시 실행하면 GPT를 써요."
                },
            )
        }

        Card {
            Text("코코 부르기", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Body("사람 모양 접근성 버튼을 누르고 말하면, AI가 목표를 정하고 점이 첫 버튼을 가리켜요. 안내 중에 다시 누르면 멈춰요.")
        }

        Card {
            Text("테스트: 말 대신 글로", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Body("음성 대신 문장을 넣어 순위 요청을 시험해요. 누르면 홈 화면으로 나가서 안내가 시작돼요.")
            OutlinedTextField(
                value = sentence,
                onValueChange = { sentence = it },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton("순위 요청하고 안내 시작") { onRankTest(sentence) }
            if (lastResult != null) {
                Text("마지막 결과", fontSize = 13.sp, color = Muted)
                Text(lastResult, fontSize = 13.sp, color = Ink, fontFamily = FontFamily.Monospace, lineHeight = 19.sp)
            }
        }

        Card {
            Text("테스트: AI 없이 (M1)", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            OutlinedButton(
                onClick = onM1Test,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("유튜브 '리센느' 검색 안내 바로 시작", fontSize = 15.sp, color = CocoBlue) }
        }
    }
}

@Composable
private fun TitleRow(title: String, status: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.weight(1f))
        Text(
            status,
            fontSize = 13.sp,
            color = if (ok) Ok else Muted,
            modifier = Modifier
                .clip(CircleShape)
                .border(1.dp, Line, CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Body(text: String) {
    Text(text, fontSize = 15.sp, color = Ink, lineHeight = 22.sp)
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = CocoBlue),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(label, fontSize = 16.sp) }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .border(1.dp, Line, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}
