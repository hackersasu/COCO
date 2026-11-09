package com.example.practice.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.practice.Config

/**
 * 지금 화면에서 스텝의 target 노드를 찾아 화면 좌표(Rect)로 돌려줘요.
 * HC-6: 화면 노드는 기기 안에서 버튼을 찾는 데만 쓰고, 비밀번호 칸은 읽지 않아요.
 *
 * 속도가 중요해요. 노드를 하나 읽을 때마다 상대 앱(예: 유튜브)에 물어봐야 해서,
 * 화면 전체를 자주 훑으면 그 앱이 느려지거나 멈출 수 있어요. 그래서
 * 1) viewId나 글자로 앱 안에서 한 번에 찾아 주는 API를 먼저 쓰고,
 * 2) 한 번 판단하는 동안(newFrame ~ 다음 newFrame) 같은 조건은 다시 찾지 않고,
 * 3) 전체 훑기는 최후의 수단으로 FULL_SCAN_MIN_INTERVAL_MS에 한 번만 해요.
 */
class NodeFinder(private val service: AccessibilityService) {

    private val frameCache = HashMap<Target, AccessibilityNodeInfo?>()
    private var lastFullScanAt = 0L

    /** 화면 판단을 새로 시작할 때 불러요. 이전 판단의 결과를 버려요. */
    fun newFrame() = frameCache.clear()

    /** 조건에 맞는 첫 번째 노드의 화면 좌표. 없으면 null. */
    fun find(target: Target): Rect? = findNode(target)?.let(::boundsOf)

    fun exists(target: Target): Boolean = find(target) != null

    /**
     * 개발용: 지금 화면에서 글자나 설명에 word가 들어간 노드를 전부 적어요(선택자 찾기용).
     * 영상이 재생 중이라 uiautomator dump가 안 될 때 써요. 화면 전체를 훑으니 자주 부르지 마세요.
     */
    fun debugDump(word: String): List<String> {
        val root = service.rootInActiveWindow ?: return listOf("(화면 없음)")
        val out = mutableListOf<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addFirst(root)
        val w = word.lowercase()
        while (stack.isNotEmpty() && out.size < 40) {
            val n = stack.removeFirst()
            if (n.isPassword) continue
            val text = n.text?.toString().orEmpty()
            val desc = n.contentDescription?.toString().orEmpty()
            if (w.isEmpty() || text.lowercase().contains(w) || desc.lowercase().contains(w)) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                out += "${n.className?.toString()?.substringAfterLast('.')} id=${n.viewIdResourceName?.substringAfterLast('/')} " +
                    "click=${n.isClickable} vis=${n.isVisibleToUser} $r text='${text.take(60)}' desc='${desc.take(120)}'"
            }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addFirst(it) }
        }
        return out
    }

    /**
     * 입력 칸 같은 노드에 지금 적힌 글자. 안내 문구(힌트)만 보이면 빈 글자로 봐요.
     * 비밀번호 칸은 읽지 않아요(HC-6). 이 글자는 기기 밖으로 보내지 않아요.
     */
    fun textOf(target: Target): String? {
        val node = findNode(target) ?: return null
        if (node.isPassword) return null
        val text = node.text?.toString().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() else null
        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        return if (showingHint || (hint != null && text == hint)) "" else text
    }

    private fun findNode(target: Target): AccessibilityNodeInfo? {
        if (target.isEmpty) return null
        if (frameCache.containsKey(target)) return frameCache[target]
        val result = service.rootInActiveWindow?.let { findIn(it, target) }
        frameCache[target] = result
        return result
    }

    private fun findIn(root: AccessibilityNodeInfo, t: Target): AccessibilityNodeInfo? {
        // 0) "이 목록 안에서" 찾기: 먼저 목록을 빠르게 찾고, 그 안만 차례로 훑어요.
        t.within?.let { w ->
            val container = findNode(w) ?: return null
            return fullScan(container, t.copy(within = null), WITHIN_SCAN_NODES)
        }
        // 1) 빠른 길: 앱이 자기 안에서 한 번에 찾아서 돌려줘요.
        if (t.viewId != null) {
            val id = if (t.viewId.contains(":id/")) t.viewId else "${root.packageName}:id/${t.viewId}"
            // viewId는 확실한 조건이라, 여기서 못 찾으면 화면에 없는 걸로 봐요.
            return root.findAccessibilityNodeInfosByViewId(id).firstOrNull { usable(it, t) }
        }
        val words = (t.text + t.desc + t.textContains + t.descContains).distinct()
        for (word in words) {
            root.findAccessibilityNodeInfosByText(word).firstOrNull { usable(it, t) }?.let { return it }
        }

        // 2) 느린 길: 전체 훑기. 앱을 느리게 만들 수 있어서 가끔만 해요.
        val now = SystemClock.uptimeMillis()
        if (now - lastFullScanAt < Config.FULL_SCAN_MIN_INTERVAL_MS) return null
        lastFullScanAt = now
        return fullScan(root, t)
    }

    private fun fullScan(root: AccessibilityNodeInfo, t: Target, limit: Int = MAX_SCAN_NODES): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addFirst(root)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < limit) {
            val node = stack.removeFirst()
            if (usable(node, t)) return node
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addFirst(it) }
            }
        }
        return null
    }

    /** 비밀번호 칸은 열어 보지도 않고(HC-6), 화면에 실제로 보이는 것만 써요. */
    private fun usable(n: AccessibilityNodeInfo, t: Target): Boolean =
        !n.isPassword && n.isVisibleToUser && matches(n, t)

    private fun matches(n: AccessibilityNodeInfo, t: Target): Boolean {
        t.viewId?.let { id ->
            val vid = n.viewIdResourceName ?: return false
            if (vid != id && !vid.endsWith(":id/$id")) return false
        }
        val desc = n.contentDescription?.toString()
        val text = n.text?.toString()
        if (t.desc.isNotEmpty() && t.desc.none { it == desc }) return false
        if (t.text.isNotEmpty() && t.text.none { it == text }) return false
        if (t.textContains.isNotEmpty() && (text == null || t.textContains.none { text.contains(it) })) return false
        if (t.descContains.isNotEmpty() && (desc == null || t.descContains.none { desc.contains(it) })) return false
        t.className?.let { if (n.className?.toString() != it) return false }
        if (t.enabledOnly && !n.isEnabled) return false
        return true
    }

    /** 찾은 노드가 눌리지 않는 글자 같은 거면, 눌리는 부모까지 올라가서 그 영역을 써요. */
    private fun boundsOf(node: AccessibilityNodeInfo): Rect? {
        var cur = node
        var hops = 0
        while (!cur.isClickable && hops++ < MAX_PARENT_HOPS) {
            cur = cur.parent ?: break
        }
        val target = if (cur.isClickable) cur else node
        return Rect().also { target.getBoundsInScreen(it) }.takeUnless { it.isEmpty }
    }

    private companion object {
        const val MAX_PARENT_HOPS = 5
        const val MAX_SCAN_NODES = 1500
        const val WITHIN_SCAN_NODES = 3000
    }
}
