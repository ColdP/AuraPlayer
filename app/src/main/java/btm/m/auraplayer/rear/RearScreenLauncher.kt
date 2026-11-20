// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.auraplayer.rear

import android.content.Context
import android.hardware.display.DisplayManager

data class RearCastResult(
    val success: Boolean,
    val summaryKey: String,
    val summaryFallback: String,
    val detail: String = "",
)

object RearScreenLauncher {
    const val SUBSCREEN_CENTER_PACKAGE = "com.xiaomi.subscreencenter"
    const val SUBSCREEN_LAUNCHER_COMPONENT = "$SUBSCREEN_CENTER_PACKAGE/com.xiaomi.subscreencenter.SubScreenLauncher"
    const val DEFAULT_REAR_DISPLAY_ID = 1

    fun buildRearCastCommand(
        packageName: String,
        activityClassName: String,
        targetDisplayId: Int = DEFAULT_REAR_DISPLAY_ID,
        restartSubscreenBeforeCast: Boolean = true,
    ): String {
        val component = "$packageName/$activityClassName"
        val shortComponent = if (activityClassName.startsWith(packageName)) {
            "$packageName/.${activityClassName.removePrefix("$packageName.")}"
        } else {
            component
        }
        val quotedComponent = shellQuote(component)
        val quotedShortComponent = shellQuote(shortComponent)
        val quotedSubscreen = shellQuote(SUBSCREEN_CENTER_PACKAGE)
        val stopSubscreenBlock = if (restartSubscreenBeforeCast) {
            """
                |subscreen=$quotedSubscreen
                |# Stop the stock rear launcher so it cannot immediately reclaim display $targetDisplayId.
                |am force-stop "${'$'}subscreen" >/dev/null 2>&1 || true
            """.trimMargin() + "\n"
        } else {
            ""
        }
        return """
            |component=$quotedComponent
            |short_component=$quotedShortComponent
            |target_display=$targetDisplayId
            |on_target_display() {
            |    am stack list 2>/dev/null | awk -v disp="displayId=${'$'}target_display" -v comp="${'$'}component" -v short_comp="${'$'}short_component" '
            |        /RootTask|Stack/ {
            |            in_target = ($0 ~ (disp "([^0-9]|$)"))
            |        }
            |        in_target && index($0, "taskId=") > 0 && (index($0, comp) > 0 || index($0, short_comp) > 0) {
            |            found = 1; exit
            |        }
            |        ($0 ~ (disp "([^0-9]|$)")) && index($0, "taskId=") > 0 && (index($0, comp) > 0 || index($0, short_comp) > 0) {
            |            found = 1; exit
            |        }
            |        END { exit(found ? 0 : 1) }
            |    '
            |}
            |find_task_id() {
            |    am stack list 2>/dev/null | awk -v comp="${'$'}component" -v short_comp="${'$'}short_component" '
            |        /RootTask|Stack/ {
            |            cur_root = $0
            |            if (sub(/.*RootTask id=/, "", cur_root) || sub(/.*Stack id=/, "", cur_root)) {
            |                sub(/[^0-9].*/, "", cur_root)
            |            } else {
            |                cur_root = ""
            |            }
            |        }
            |        index($0, comp) > 0 || index($0, short_comp) > 0 {
            |            tid = $0
            |            if (sub(/.*taskId=/, "", tid)) {
            |                sub(/[^0-9].*/, "", tid)
            |            } else {
            |                tid = ""
            |            }
            |            if (cur_root != "") res = cur_root
            |            else if (tid != "") res = tid
            |        }
            |        END {
            |            if (res != "") print res
            |        }
            |    '
            |}
            |move_task_to_target() {
            |    tid="$1"
            |    service call activity_task 50 i32 "${'$'}tid" i32 "${'$'}target_display" >/dev/null 2>&1 || true
            |    for _j in 1 2 3 4 5 6; do
            |        if on_target_display; then
            |            return 0
            |        fi
            |        sleep 0.08
            |    done
            |    am display move-stack "${'$'}tid" "${'$'}target_display" >/dev/null 2>&1 || true
            |    for _j in 1 2 3 4 5 6; do
            |        if on_target_display; then
            |            return 0
            |        fi
            |        sleep 0.08
            |    done
            |    return 1
            |}
            |""".trimMargin() + "\n" + stopSubscreenBlock + """
            |existing_id="${'$'}(find_task_id)"
            |if [ -n "${'$'}existing_id" ] && ! on_target_display; then
            |    if move_task_to_target "${'$'}existing_id"; then
            |        am start --user 0 --display "${'$'}target_display" -f 0x10000000 -n "${'$'}component" >/dev/null 2>&1 || true
            |        if on_target_display; then
            |            echo "OK_MOVED_EXISTING:${'$'}existing_id"
            |            exit 0
            |        fi
            |    fi
            |fi
            |am start --user 0 --display "${'$'}target_display" -f 0x10000000 -n "${'$'}component" >/dev/null 2>&1 || true
            |for _k in 1 2 3 4 5 6 7 8; do
            |    if on_target_display; then
            |        echo "OK_DIRECT"
            |        exit 0
            |    fi
            |    sleep 0.08
            |done
            |am start --user 0 --display "${'$'}target_display" -f 0x10000000 -n "${'$'}component" >/dev/null 2>&1 || true
            |for _k in 1 2 3 4; do
            |    if on_target_display; then
            |        echo "OK_DIRECT_RETRY"
            |        exit 0
            |    fi
            |    sleep 0.08
            |done
            |# Fallback: create the task normally, discover its id, then move it to target_display.
            |am start --user 0 -f 0x10000000 -n "${'$'}component" >/dev/null 2>&1 || true
            |task_id=""
            |for _i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40; do
            |    if on_target_display; then
            |        echo "OK_DIRECT_LATE"
            |        exit 0
            |    fi
            |    task_id="${'$'}(find_task_id)"
            |    [ -n "${'$'}task_id" ] && break
            |    sleep 0.05
            |done
            |if [ -n "${'$'}task_id" ] && move_task_to_target "${'$'}task_id"; then
            |    echo "OK_MOVED:${'$'}task_id"
            |    exit 0
            |fi
            |echo "ERR_CAST_FAILED"
            |exit 1
        """.trimMargin()
    }

    fun parseTaskIdForRearMove(
        stackListOutput: String,
        packageName: String,
        activityClassName: String? = null,
    ): Int? {
        val lines = stackListOutput.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val shortComponent = activityClassName?.let {
            if (it.startsWith(packageName)) "$packageName/.${it.removePrefix("$packageName.")}" else "$packageName/$it"
        }
        val preferred = if (activityClassName != null) {
            lines.lastOrNull { line ->
                line.contains("$packageName/$activityClassName") ||
                    (shortComponent != null && line.contains(shortComponent))
            }
        } else null
        val matched = preferred ?: lines.lastOrNull { it.contains(packageName) } ?: return null
        val match = Regex("""taskId=(\d+)""").find(matched) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    fun hasRearDisplay(context: Context, targetDisplayId: Int = DEFAULT_REAR_DISPLAY_ID): Boolean {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return false
        return manager.getDisplay(targetDisplayId) != null
    }

    suspend fun castToRearDisplay(
        context: Context,
        privilegeManager: RearScreenPrivilegeManager,
        activityClassName: String,
        targetDisplayId: Int = DEFAULT_REAR_DISPLAY_ID,
        restartSubscreenBeforeCast: Boolean = true,
    ): RearCastResult {
        val state = privilegeManager.refresh(forceRootProbe = false)
        if (!state.isAuthorized) {
            return RearCastResult(
                success = false,
                summaryKey = "rear_screen_perm_unauthorized",
                summaryFallback = "未授权",
            )
        }
        val command = buildRearCastCommand(
            packageName = context.packageName,
            activityClassName = activityClassName,
            targetDisplayId = targetDisplayId,
            restartSubscreenBeforeCast = restartSubscreenBeforeCast,
        )
        val result = privilegeManager.executeCommand(command)
        return if (result.isSuccess && result.output.contains("OK_")) {
            RearCastResult(
                success = true,
                summaryKey = "rear_screen_cast_success",
                summaryFallback = "已尝试投放到背屏",
                detail = result.output,
            )
        } else {
            RearCastResult(
                success = false,
                summaryKey = "rear_screen_cast_failed",
                summaryFallback = "投放到背屏失败，请确认背屏已点亮且权限正常",
                detail = result.output,
            )
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
