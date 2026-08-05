package com.mohnishraj.aether.core.browser

import com.mohnishraj.aether.core.browser.storage.StorageQuotaExceededException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserStorageFormsTest {
    @Test fun localStoragePersistsAcrossPagesOnSameOrigin() {
        val runtime = browserRuntime()
        val first = runtime.browser.open(PAGE_HTML, "https://example.test/one")
        first.localStorage.setItem("theme", "dark")
        val second = runtime.browser.open(PAGE_HTML, "https://example.test/two")
        assertEquals("dark", second.localStorage.getItem("theme"))
    }

    @Test fun localStorageIsPartitionedByOrigin() {
        val runtime = browserRuntime()
        runtime.browser.open(PAGE_HTML, "https://one.test/").localStorage.setItem("key", "value")
        assertNull(runtime.browser.open(PAGE_HTML, "https://two.test/").localStorage.getItem("key"))
    }

    @Test fun sessionStoragePersistsForRuntimeAndCanBeCleared() {
        val runtime = browserRuntime()
        val page = runtime.browser.open(PAGE_HTML, "https://example.test/")
        page.sessionStorage.setItem("token", "abc")
        assertEquals("abc", runtime.browser.open(PAGE_HTML, "https://example.test/next").sessionStorage.getItem("token"))
        runtime.browser.clearSession(page.origin)
        assertNull(runtime.browser.open(PAGE_HTML, "https://example.test/again").sessionStorage.getItem("token"))
    }

    @Test fun storageSupportsEmptyKeyAndValue() {
        val runtime = browserRuntime()
        val first = runtime.browser.open(PAGE_HTML, "https://empty-key.test/")
        first.localStorage.setItem("", "")
        val second = runtime.browser.open(PAGE_HTML, "https://empty-key.test/next")
        assertEquals("", second.localStorage.getItem(""))
    }

    @Test fun storageKeyOrderAndRemovalWork() {
        val area = browserRuntime().browser.open(PAGE_HTML).localStorage
        area.setItem("a", "1")
        area.setItem("b", "2")
        assertEquals("a", area.key(0))
        area.removeItem("a")
        assertEquals(1, area.length)
        area.clear()
        assertEquals(0, area.length)
    }

    @Test fun storageQuotaIsEnforced() {
        val runtime = browserRuntime()
        val area = runtime.browser.open(PAGE_HTML).localStorage
        repeat(5) { index -> area.setItem("chunk-$index", "x".repeat(900_000)) }
        assertFailsWith<StorageQuotaExceededException> { area.setItem("overflow", "y".repeat(900_000)) }
    }

    @Test fun formControlsAreDiscovered() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val form = page.document.getElementById("signup")!!
        assertEquals(3, page.forms.controls(form).size)
    }

    @Test fun validFormSerializesFields() {
        val page = browserRuntime().browser.open(PAGE_HTML, "https://example.test/start")
        val form = page.document.getElementById("signup")!!
        val submission = page.forms.serialize(form, page.url)
        assertTrue(submission.validation.valid)
        assertEquals("POST", submission.method)
        assertEquals("/join", submission.action)
        assertTrue(submission.encodedBody.contains("email=dev%40example.com"))
        assertTrue(submission.encodedBody.contains("role=engine"))
    }

    @Test fun invalidEmailFailsValidation() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val form = page.document.getElementById("signup")!!
        val email = page.document.querySelector("input[type=email]")!!
        page.forms.setValue(email, "bad")
        val result = page.forms.validate(form)
        assertFalse(result.valid)
        assertEquals("Invalid email address", result.issues.single().message)
    }

    @Test fun requiredEmptyFieldFailsValidation() {
        val page = browserRuntime().browser.open(PAGE_HTML)
        val form = page.document.getElementById("signup")!!
        val email = page.document.querySelector("input[name=email]")!!
        page.forms.setValue(email, "")
        assertTrue(page.forms.validate(form).issues.any { it.message == "This field is required" })
    }

    @Test fun checkedCheckboxSerializes() {
        val page = browserRuntime().browser.open("<form id='f'><input type='checkbox' name='news' value='yes' checked></form>")
        val submission = page.forms.serialize(page.document.getElementById("f")!!)
        assertEquals(listOf("news" to "yes"), submission.fields.map { it.name to it.value })
    }

    @Test fun uncheckedCheckboxIsOmitted() {
        val page = browserRuntime().browser.open("<form id='f'><input type='checkbox' name='news' value='yes'></form>")
        assertTrue(page.forms.serialize(page.document.getElementById("f")!!).fields.isEmpty())
    }

    @Test fun selectValueCanBeReadAndChanged() {
        val page = browserRuntime().browser.open("<form><select id='s' name='x'><option value='a'>A</option><option value='b'>B</option></select></form>")
        val select = page.document.getElementById("s")!!
        assertEquals("a", page.forms.value(select))
        page.forms.setValue(select, "b")
        assertEquals("b", page.forms.value(select))
    }
}
