package com.ketronkowski.xlights.preshow

import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Gmail-friendly HTML report (inline styles only), in the same look as the
 * homelab health report: white card, status banner, one table per layer with a
 * blue header row and green / yellow / red rows.
 */
@Component
class ReportRenderer(private val props: PreShowProperties) {

    private val layerIcons = mapOf(
        "Setup" to "🧾", "Power" to "🔌", "Boards" to "💡", "FPP" to "🎵", "Camera" to "📷", "Web" to "🌐",
    )

    fun subject(run: PreShowRun): String =
        "Pre-show check — ${dateFmt().format(run.startedAt)} (${run.pass}): ${run.verdict.label}"

    fun render(run: PreShowRun): String {
        val zone = ZoneId.of(props.zone)
        val time = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a").withZone(zone)
        val (bannerBg, bannerFg, icon) = when (run.verdict) {
            Verdict.GO -> Triple("#d4edda", "#155724", "✅")
            Verdict.GO_WITH_WARNINGS -> Triple("#fff3cd", "#856404", "⚠️")
            Verdict.NO_GO -> Triple("#f8d7da", "#721c24", "⛔")
        }
        val secs = java.time.Duration.between(run.startedAt, run.finishedAt).seconds
        val sb = StringBuilder()
        sb.append("""<!doctype html><html><body style="margin:0;padding:16px;background:#f1f3f4;font-family:Arial,Helvetica,sans-serif;color:#202124">""")
        sb.append("""<div style="background:#ffffff;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,0.15);max-width:700px;margin:0 auto;padding:24px">""")
        sb.append("""<h1 style="font-size:24px;margin:0 0 12px 0">Pre-show Check — ${esc(time.format(run.startedAt))}</h1>""")
        sb.append("""<div style="background:$bannerBg;color:$bannerFg;border-radius:6px;padding:12px;font-weight:bold;text-align:center;font-size:16px">$icon ${esc(run.headline())}</div>""")
        sb.append("""<p style="font-size:13px;color:#5f6368;margin:12px 0 0 0">Pass: <b>${esc(run.pass)}</b> · took $secs s""")
        run.layoutUploadedAt?.let { sb.append(" · xLights layout uploaded ${esc(time.format(it))}") }
        sb.append("</p>")

        val problems = run.failures + run.warnings
        if (problems.isNotEmpty()) {
            sb.append(section("🚨", "Needs attention"))
            sb.append(table(problems))
        }

        val order = layerIcons.keys.toList()
        val layers = run.results.map { it.layer }.distinct()
            .sortedBy { order.indexOf(it).let { i -> if (i < 0) order.size else i } }
        for (layer in layers) {
            val rows = run.results.filter { it.layer == layer }
            sb.append(section(layerIcons[layer] ?: "🔎", layer))
            sb.append(table(rows))
        }
        sb.append("""<p style="font-size:12px;color:#5f6368;margin-top:24px">Read-only check. Nothing on the show was changed. Run id ${esc(run.id)}.</p>""")
        sb.append("</div></body></html>")
        return sb.toString()
    }

    private fun section(icon: String, title: String) =
        """<h2 style="font-size:18px;margin:24px 0 8px 0;padding-bottom:4px;border-bottom:2px solid #1a73e8">$icon ${esc(title)}</h2>"""

    private fun table(rows: List<CheckResult>): String {
        val sb = StringBuilder()
        sb.append("""<table style="width:100%;border-collapse:collapse;font-size:13px">""")
        sb.append("""<tr style="background:#1a73e8;color:#ffffff;font-weight:bold">""")
        for (h in listOf("Status", "Target", "Check", "Details")) sb.append("""<td style="padding:6px 8px">$h</td>""")
        sb.append("</tr>")
        for (r in rows) {
            val (bg, fg, label) = when (r.outcome) {
                Outcome.PASS -> Triple("#d4edda", "#155724", "PASS")
                Outcome.WARN -> Triple("#fff3cd", "#856404", "WARN")
                Outcome.FAIL -> Triple("#f8d7da", "#721c24", "FAIL")
                Outcome.SKIP -> Triple("#f1f3f4", "#5f6368", "SKIP")
            }
            sb.append("""<tr style="background:$bg;color:$fg">""")
            sb.append("""<td style="padding:6px 8px;font-weight:bold;border-top:1px solid #ffffff">$label</td>""")
            sb.append("""<td style="padding:6px 8px;border-top:1px solid #ffffff">${esc(r.target)}</td>""")
            sb.append("""<td style="padding:6px 8px;border-top:1px solid #ffffff">${esc(r.name)}</td>""")
            sb.append("""<td style="padding:6px 8px;border-top:1px solid #ffffff">${esc(r.message)}</td>""")
            sb.append("</tr>")
        }
        sb.append("</table>")
        return sb.toString()
    }

    private fun dateFmt() = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of(props.zone))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
