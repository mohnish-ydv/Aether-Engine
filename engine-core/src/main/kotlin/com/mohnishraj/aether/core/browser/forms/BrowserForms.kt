package com.mohnishraj.aether.core.browser.forms

import com.mohnishraj.aether.core.browser.dom.BrowserDocument
import com.mohnishraj.aether.core.html.dom.ElementNode
import java.net.URLEncoder
import java.util.Locale

data class FormField(val name: String, val value: String)
data class FormValidationIssue(val controlId: Long, val name: String?, val message: String)
data class FormValidationResult(val valid: Boolean, val issues: List<FormValidationIssue>)
data class FormSubmission(
    val method: String,
    val action: String,
    val enctype: String,
    val fields: List<FormField>,
    val encodedBody: String,
    val validation: FormValidationResult
)

class BrowserFormController(private val document: BrowserDocument) {
    fun controls(form: ElementNode): List<ElementNode> {
        require(form.localName == "form") { "Expected a form element" }
        return form.descendants().filterIsInstance<ElementNode>()
            .filter { it.localName in setOf("input", "textarea", "select", "button") }
            .toList()
    }

    fun value(control: ElementNode): String = when (control.localName) {
        "textarea" -> control.textContent
        "select" -> {
            val options = control.getElementsByTagName("option")
            val selected = options.firstOrNull { it.hasAttribute("selected") } ?: options.firstOrNull()
            selected?.getAttribute("value") ?: selected?.textContent.orEmpty()
        }
        else -> control.getAttribute("value").orEmpty()
    }

    fun setValue(control: ElementNode, value: String) {
        when (control.localName) {
            "textarea" -> document.setTextContent(control, value)
            "select" -> {
                control.getElementsByTagName("option").forEach { option ->
                    if ((option.getAttribute("value") ?: option.textContent) == value) document.setAttribute(option, "selected", "")
                    else document.removeAttribute(option, "selected")
                }
            }
            else -> document.setAttribute(control, "value", value)
        }
    }

    fun validate(form: ElementNode): FormValidationResult {
        val issues = mutableListOf<FormValidationIssue>()
        controls(form).forEach { control ->
            if (control.hasAttribute("disabled") || control.localName == "button") return@forEach
            val name = control.getAttribute("name")
            val value = value(control)
            if (control.hasAttribute("required") && value.isBlank() && !isChecked(control)) {
                issues += FormValidationIssue(control.nodeId, name, "This field is required")
            }
            control.getAttribute("minlength")?.toIntOrNull()?.let { minimum ->
                if (value.length < minimum) issues += FormValidationIssue(control.nodeId, name, "Minimum length is $minimum")
            }
            control.getAttribute("maxlength")?.toIntOrNull()?.let { maximum ->
                if (value.length > maximum) issues += FormValidationIssue(control.nodeId, name, "Maximum length is $maximum")
            }
            control.getAttribute("pattern")?.let { pattern ->
                val matches = runCatching { Regex("^(?:$pattern)$").matches(value) }.getOrDefault(false)
                if (value.isNotEmpty() && !matches) issues += FormValidationIssue(control.nodeId, name, "Value does not match pattern")
            }
            when (control.getAttribute("type")?.lowercase(Locale.ROOT)) {
                "email" -> if (value.isNotEmpty() && !EMAIL.matches(value)) issues += FormValidationIssue(control.nodeId, name, "Invalid email address")
                "number" -> if (value.isNotEmpty() && value.toDoubleOrNull() == null) issues += FormValidationIssue(control.nodeId, name, "Invalid number")
            }
        }
        return FormValidationResult(issues.isEmpty(), issues)
    }

    fun serialize(form: ElementNode, documentUrl: String = "about:blank"): FormSubmission {
        val validation = validate(form)
        val fields = buildList {
            controls(form).forEach { control ->
                if (control.hasAttribute("disabled")) return@forEach
                val name = control.getAttribute("name") ?: return@forEach
                val type = control.getAttribute("type")?.lowercase(Locale.ROOT)
                if (type in setOf("submit", "button", "reset", "file")) return@forEach
                if (type in setOf("checkbox", "radio") && !isChecked(control)) return@forEach
                if (control.localName == "select" && control.hasAttribute("multiple")) {
                    control.getElementsByTagName("option").filter { it.hasAttribute("selected") }.forEach { option ->
                        add(FormField(name, option.getAttribute("value") ?: option.textContent))
                    }
                } else add(FormField(name, value(control)))
            }
        }
        val body = fields.joinToString("&") { field -> encode(field.name) + "=" + encode(field.value) }
        return FormSubmission(
            method = form.getAttribute("method")?.uppercase(Locale.ROOT) ?: "GET",
            action = form.getAttribute("action")?.ifBlank { documentUrl } ?: documentUrl,
            enctype = form.getAttribute("enctype") ?: "application/x-www-form-urlencoded",
            fields = fields,
            encodedBody = body,
            validation = validation
        )
    }

    private fun isChecked(control: ElementNode): Boolean = control.hasAttribute("checked") || control.getAttribute("aria-checked") == "true"
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
