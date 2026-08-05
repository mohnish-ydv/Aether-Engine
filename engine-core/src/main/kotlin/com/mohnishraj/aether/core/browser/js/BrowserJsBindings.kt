package com.mohnishraj.aether.core.browser.js

import com.mohnishraj.aether.core.browser.BrowserApiCounters
import com.mohnishraj.aether.core.browser.BrowserApiLimits
import com.mohnishraj.aether.core.browser.BrowserPage
import com.mohnishraj.aether.core.browser.ClipboardPort
import com.mohnishraj.aether.core.browser.events.BrowserEvent
import com.mohnishraj.aether.core.browser.events.BrowserEventListener
import com.mohnishraj.aether.core.browser.events.EventListenerHandle
import com.mohnishraj.aether.core.browser.events.EventListenerOptions
import com.mohnishraj.aether.core.browser.fetch.BrowserFetchRequest
import com.mohnishraj.aether.core.browser.mutation.MutationObserverOptions
import com.mohnishraj.aether.core.browser.storage.BrowserStorageArea
import com.mohnishraj.aether.core.html.dom.DomNode
import com.mohnishraj.aether.core.html.dom.DomNodeType
import com.mohnishraj.aether.core.html.dom.ElementNode
import com.mohnishraj.aether.core.js.JsExecutionContext
import com.mohnishraj.aether.core.js.JsJson
import com.mohnishraj.aether.core.js.JsNativeFunction
import com.mohnishraj.aether.core.js.JsPromiseValue
import com.mohnishraj.aether.core.js.JsRuntimeException
import com.mohnishraj.aether.core.js.JsValue
import com.mohnishraj.aether.core.security.PermissionFeature
import java.util.IdentityHashMap
import java.util.Locale

internal class BrowserJsBindings(
    private val page: BrowserPage,
    private val clipboard: ClipboardPort,
    private val limits: BrowserApiLimits,
    private val counters: BrowserApiCounters
) {
    private val objectToNode = IdentityHashMap<JsValue.ObjectValue, DomNode>()
    private val nodeToObject = linkedMapOf<Long, JsValue.ObjectValue>()
    private val propertyListenerHandles = linkedMapOf<Pair<Long, String>, EventListenerHandle>()
    private lateinit var documentValue: LiveObject
    private lateinit var windowValue: LiveObject

    fun globals(): Map<String, JsValue> {
        documentValue = documentObject()
        val localStorage = storageObject(page.localStorage, "localStorage")
        val sessionStorage = storageObject(page.sessionStorage, "sessionStorage")
        val clipboardObject = clipboardObject()
        val navigator = LiveObject(
            getters = mapOf(
                "clipboard" to { clipboardObject },
                "userAgent" to { JsValue.StringValue("AetherEngine/0.18 (Android; native custom engine)") },
                "language" to { JsValue.StringValue(Locale.getDefault().toLanguageTag()) },
                "onLine" to { JsValue.BooleanValue(true) }
            )
        )
        val location = locationObject()
        val fetchSync = native("fetchSync", 2) { _, _, arguments -> fetchResponse(arguments, promiseBody = false) }
        val fetch = native("fetch", 2) { _, _, arguments -> JsPromiseValue.fulfilled(fetchResponse(arguments, promiseBody = true)) }
        val observe = native("observeMutations", 3) { context, _, arguments -> observeMutations(context, arguments) }
        val serializeForm = native("serializeForm", 1) { _, _, arguments -> serializeForm(arguments) }
        val eventConstructor = native("Event", 2) { _, _, arguments -> eventConstructor(arguments) }
        val mutationObserver = native("MutationObserver", 1) { context, _, arguments -> mutationObserverObject(context, arguments) }
        val xmlHttpRequest = native("XMLHttpRequest", 0) { _, _, _ -> xmlHttpRequestObject() }
        val getComputedStyle = native("getComputedStyle", 1) { _, _, arguments -> computedStyleObject(nodeArg(arguments, 0) as? ElementNode) }
        val requestAnimationFrame = native("requestAnimationFrame", 1) { context, _, arguments ->
            val callback = arguments.firstOrNull() as? JsValue.FunctionValue
                ?: throw JsRuntimeException("TypeError", "requestAnimationFrame callback must be a function")
            val wrapper = native("animationFrame", 0) { callbackContext, _, _ ->
                callbackContext.call(callback, windowValue, listOf(JsValue.NumberValue(page.monotonicMillis().toDouble())))
            }
            JsValue.NumberValue(context.schedule(wrapper, 0L, emptyList(), microtask = true).toDouble())
        }
        val cancelAnimationFrame = native("cancelAnimationFrame", 1) { context, _, arguments ->
            context.cancelTimer(numberArg(arguments, 0).toLong())
            JsValue.Undefined
        }
        val setTimeout = native("setTimeout", 2) { context, _, arguments ->
            val callback = arguments.firstOrNull() as? JsValue.FunctionValue
                ?: throw JsRuntimeException("TypeError", "setTimeout callback must be a function")
            JsValue.NumberValue(context.schedule(callback, (arguments.getOrNull(1)?.toNumber() ?: 0.0).toLong(), arguments.drop(2)).toDouble())
        }
        val clearTimeout = native("clearTimeout", 1) { context, _, arguments ->
            context.cancelTimer((arguments.firstOrNull()?.toNumber() ?: 0.0).toLong())
            JsValue.Undefined
        }
        val setInterval = native("setInterval", 2) { context, _, arguments ->
            val callback = arguments.firstOrNull() as? JsValue.FunctionValue
                ?: throw JsRuntimeException("TypeError", "setInterval callback must be a function")
            val delay = (arguments.getOrNull(1)?.toNumber() ?: 0.0).toLong().coerceAtLeast(1L)
            JsValue.NumberValue(context.schedule(callback, delay, arguments.drop(2), intervalMillis = delay).toDouble())
        }
        val queueMicrotask = native("queueMicrotask", 1) { context, _, arguments ->
            val callback = arguments.firstOrNull() as? JsValue.FunctionValue
                ?: throw JsRuntimeException("TypeError", "queueMicrotask callback must be a function")
            context.schedule(callback, 0L, emptyList(), microtask = true)
            JsValue.Undefined
        }
        val browserConsole = JsValue.ObjectValue().also { console ->
            listOf("log", "info", "warn", "error", "debug").forEach { level ->
                console.properties[level] = native(level, 1) { context, _, arguments ->
                    val message = arguments.joinToString(" ") { it.displayString() }
                    page.consoleMessage("[$level] $message")
                    context.log(arguments)
                    JsValue.Undefined
                }
            }
        }
        val performance = LiveObject(getters = mapOf(
            "timeOrigin" to { JsValue.NumberValue(System.currentTimeMillis().toDouble() - page.monotonicMillis()) }
        )).also { value ->
            value.properties["now"] = native("now", 0) { _, _, _ -> JsValue.NumberValue(page.monotonicMillis().toDouble()) }
        }
        val screen = LiveObject(getters = mapOf(
            "width" to { JsValue.NumberValue(page.viewportWidth) },
            "height" to { JsValue.NumberValue(page.viewportHeight) },
            "availWidth" to { JsValue.NumberValue(page.viewportWidth) },
            "availHeight" to { JsValue.NumberValue(page.viewportHeight) }
        ))

        windowValue = LiveObject(
            getters = mapOf(
                "document" to { documentValue },
                "localStorage" to { localStorage },
                "sessionStorage" to { sessionStorage },
                "navigator" to { navigator },
                "location" to { location },
                "console" to { browserConsole },
                "performance" to { performance },
                "screen" to { screen },
                "innerWidth" to { JsValue.NumberValue(page.viewportWidth) },
                "innerHeight" to { JsValue.NumberValue(page.viewportHeight) },
                "devicePixelRatio" to { JsValue.NumberValue(page.deviceScaleFactor) },
                "pageXOffset" to { JsValue.NumberValue(0.0) },
                "pageYOffset" to { JsValue.NumberValue(0.0) }
            ),
            dynamicSetter = { context, name, value ->
                if (name.startsWith("on") && name.length > 2) {
                    setEventHandler(context, page.document.document, name.drop(2), value)
                    true
                } else false
            }
        )
        windowValue.properties.putAll(
            mapOf(
                "fetch" to fetch,
                "fetchSync" to fetchSync,
                "observeMutations" to observe,
                "serializeForm" to serializeForm,
                "Event" to eventConstructor,
                "MutationObserver" to mutationObserver,
                "XMLHttpRequest" to xmlHttpRequest,
                "getComputedStyle" to getComputedStyle,
                "requestAnimationFrame" to requestAnimationFrame,
                "cancelAnimationFrame" to cancelAnimationFrame,
                "setTimeout" to setTimeout,
                "clearTimeout" to clearTimeout,
                "setInterval" to setInterval,
                "clearInterval" to clearTimeout,
                "queueMicrotask" to queueMicrotask,
                "addEventListener" to eventTargetAdd(page.document.document),
                "removeEventListener" to eventTargetRemove(page.document.document),
                "dispatchEvent" to eventTargetDispatch(page.document.document),
                "alert" to native("alert", 1) { _, _, arguments -> page.consoleMessage(arguments.firstOrNull()?.displayString().orEmpty()); JsValue.Undefined }
            )
        )
        windowValue.properties["window"] = windowValue
        windowValue.properties["self"] = windowValue
        windowValue.properties["globalThis"] = windowValue

        return linkedMapOf(
            "window" to windowValue,
            "self" to windowValue,
            "globalThis" to windowValue,
            "document" to documentValue,
            "localStorage" to localStorage,
            "sessionStorage" to sessionStorage,
            "navigator" to navigator,
            "location" to location,
            "fetch" to fetch,
            "fetchSync" to fetchSync,
            "observeMutations" to observe,
            "serializeForm" to serializeForm,
            "Event" to eventConstructor,
            "MutationObserver" to mutationObserver,
            "XMLHttpRequest" to xmlHttpRequest,
            "getComputedStyle" to getComputedStyle,
            "requestAnimationFrame" to requestAnimationFrame,
            "cancelAnimationFrame" to cancelAnimationFrame,
            "setTimeout" to setTimeout,
            "clearTimeout" to clearTimeout,
            "setInterval" to setInterval,
            "clearInterval" to clearTimeout,
            "queueMicrotask" to queueMicrotask,
            "console" to browserConsole,
            "performance" to performance,
            "screen" to screen
        )
    }

    private fun documentObject(): LiveObject {
        val document = LiveObject(
            getters = mapOf(
                "URL" to { JsValue.StringValue(page.url) },
                "documentURI" to { JsValue.StringValue(page.url) },
                "origin" to { JsValue.StringValue(page.origin) },
                "readyState" to { JsValue.StringValue(page.readyState) },
                "documentElement" to { nodeOrNull(page.document.documentElement) },
                "head" to { nodeOrNull(page.document.head) },
                "body" to { nodeOrNull(page.document.body) },
                "activeElement" to { nodeOrNull(page.activeElement) },
                "title" to { JsValue.StringValue(page.documentTitle()) },
                "location" to { locationObject() },
                "defaultView" to { windowValue }
            ),
            setters = mapOf(
                "title" to { _, value -> page.setDocumentTitle(value.displayString()); true }
            ),
            dynamicSetter = { context, name, value ->
                if (name.startsWith("on") && name.length > 2) {
                    setEventHandler(context, page.document.document, name.drop(2), value)
                    true
                } else false
            }
        )
        document.properties["getElementById"] = native("getElementById", 1) { _, _, args -> nodeOrNull(page.document.getElementById(stringArg(args, 0))) }
        document.properties["getElementsByTagName"] = native("getElementsByTagName", 1) { _, _, args -> nodes(page.document.getElementsByTagName(stringArg(args, 0))) }
        document.properties["getElementsByClassName"] = native("getElementsByClassName", 1) { _, _, args -> nodes(page.document.getElementsByClassName(stringArg(args, 0))) }
        document.properties["querySelector"] = native("querySelector", 1) { _, _, args -> nodeOrNull(page.document.querySelector(stringArg(args, 0))) }
        document.properties["querySelectorAll"] = native("querySelectorAll", 1) { _, _, args -> nodes(page.document.querySelectorAll(stringArg(args, 0))) }
        document.properties["createElement"] = native("createElement", 1) { _, _, args -> node(page.document.createElement(stringArg(args, 0))) }
        document.properties["createTextNode"] = native("createTextNode", 1) { _, _, args -> node(page.document.createTextNode(stringArg(args, 0))) }
        document.properties["createComment"] = native("createComment", 1) { _, _, args -> node(page.document.createComment(stringArg(args, 0))) }
        document.properties["createDocumentFragment"] = native("createDocumentFragment", 0) { _, _, _ -> node(page.document.createDocumentFragment()) }
        document.properties["addEventListener"] = eventTargetAdd(page.document.document)
        document.properties["removeEventListener"] = eventTargetRemove(page.document.document)
        document.properties["dispatchEvent"] = eventTargetDispatch(page.document.document)
        document.properties["deliverMutations"] = native("deliverMutations", 0) { _, _, _ -> JsValue.NumberValue(page.document.deliverMutations().toDouble()) }
        document.properties["write"] = native("write", 1) { _, _, args ->
            val body = page.document.body ?: return@native JsValue.Undefined
            mutate { page.document.setInnerHtml(body, page.document.innerHtml(body) + args.joinToString("") { it.displayString() }) }
            JsValue.Undefined
        }
        document.properties["writeln"] = native("writeln", 1) { _, _, args ->
            val body = page.document.body ?: return@native JsValue.Undefined
            mutate { page.document.setInnerHtml(body, page.document.innerHtml(body) + args.joinToString("") { it.displayString() } + "\n") }
            JsValue.Undefined
        }
        return document
    }

    private fun node(node: DomNode): JsValue.ObjectValue = nodeToObject.getOrPut(node.nodeId) {
        LiveObject(
            getters = nodeGetters(node),
            setters = nodeSetters(node),
            dynamicSetter = { context, name, value ->
                if (name.startsWith("on") && name.length > 2) {
                    setEventHandler(context, node, name.drop(2), value)
                    true
                } else false
            }
        ).also { value ->
            objectToNode[value] = node
            installNodeMethods(value, node)
            if (node is ElementNode) installElementMethods(value, node)
        }
    }

    private fun nodeGetters(node: DomNode): Map<String, () -> JsValue> = linkedMapOf(
        "nodeId" to { JsValue.NumberValue(node.nodeId.toDouble()) },
        "nodeName" to { JsValue.StringValue(node.nodeName) },
        "nodeType" to { JsValue.NumberValue(domNodeTypeNumber(node.nodeType).toDouble()) },
        "textContent" to { JsValue.StringValue(node.textContent) },
        "parentNode" to { nodeOrNull(node.parent) },
        "parentElement" to { nodeOrNull(node.parent as? ElementNode) },
        "childNodes" to { nodes(node.children) },
        "children" to { nodes(node.children.filterIsInstance<ElementNode>()) },
        "childElementCount" to { JsValue.NumberValue(node.children.count { it is ElementNode }.toDouble()) },
        "firstChild" to { nodeOrNull(node.firstChild) },
        "lastChild" to { nodeOrNull(node.lastChild) },
        "previousSibling" to { nodeOrNull(node.previousSibling) },
        "nextSibling" to { nodeOrNull(node.nextSibling) },
        "firstElementChild" to { nodeOrNull(node.children.filterIsInstance<ElementNode>().firstOrNull()) },
        "lastElementChild" to { nodeOrNull(node.children.filterIsInstance<ElementNode>().lastOrNull()) },
        "isConnected" to { JsValue.BooleanValue(node.root() === page.document.document) },
        "ownerDocument" to { documentValue },
        "innerHTML" to { JsValue.StringValue(page.document.innerHtml(node)) },
        "outerHTML" to { JsValue.StringValue(page.document.outerHtml(node)) }
    )

    private fun nodeSetters(node: DomNode): Map<String, (JsExecutionContext, JsValue) -> Boolean> = linkedMapOf(
        "textContent" to { _, value -> mutate { page.document.setTextContent(node, value.displayString()) }; true },
        "innerHTML" to { _, value ->
            val element = node as? ElementNode
            if (element == null) {
                false
            } else {
                mutate { page.document.setInnerHtml(element, value.displayString()) }
                true
            }
        }
    )

    private fun installNodeMethods(value: LiveObject, node: DomNode) {
        value.properties["getText"] = native("getText", 0) { _, _, _ -> JsValue.StringValue(node.textContent) }
        value.properties["setText"] = native("setText", 1) { _, _, args -> mutate { page.document.setTextContent(node, stringArg(args, 0)) }; JsValue.Undefined }
        value.properties["appendChild"] = native("appendChild", 1) { _, _, args ->
            val child = nodeArg(args, 0)
            mutate { page.document.appendChild(node, child) }
            this.node(child)
        }
        value.properties["insertBefore"] = native("insertBefore", 2) { _, _, args ->
            val child = nodeArg(args, 0)
            val reference = args.getOrNull(1)?.takeUnless { it === JsValue.Null || it === JsValue.Undefined }?.let(::nodeFromValue)
            mutate { page.document.insertBefore(node, child, reference) }
            this.node(child)
        }
        value.properties["replaceChild"] = native("replaceChild", 2) { _, _, args ->
            val newChild = nodeArg(args, 0)
            val oldChild = nodeArg(args, 1)
            mutate { page.document.replaceChild(node, newChild, oldChild) }
            this.node(oldChild)
        }
        value.properties["removeChild"] = native("removeChild", 1) { _, _, args ->
            val child = nodeArg(args, 0)
            mutate { page.document.removeChild(node, child) }
            this.node(child)
        }
        value.properties["remove"] = native("remove", 0) { _, _, _ ->
            node.parent?.let { parent -> mutate { page.document.removeChild(parent, node) } }
            JsValue.Undefined
        }
        value.properties["cloneNode"] = native("cloneNode", 1) { _, _, args -> node(page.document.cloneNode(node, args.firstOrNull()?.isTruthy() == true)) }
        value.properties["contains"] = native("contains", 1) { _, _, args ->
            val target = args.firstOrNull()?.takeUnless { it === JsValue.Null || it === JsValue.Undefined }?.let(::nodeFromValue)
            JsValue.BooleanValue(target != null && (target === node || node.descendants().any { it === target }))
        }
        value.properties["append"] = native("append", 1) { _, _, args ->
            args.forEach { item ->
                val child = (item as? JsValue.ObjectValue)?.let(objectToNode::get) ?: page.document.createTextNode(item.displayString())
                mutate { page.document.appendChild(node, child) }
            }
            JsValue.Undefined
        }
        value.properties["prepend"] = native("prepend", 1) { _, _, args ->
            args.asReversed().forEach { item ->
                val child = (item as? JsValue.ObjectValue)?.let(objectToNode::get) ?: page.document.createTextNode(item.displayString())
                mutate { page.document.insertBefore(node, child, node.firstChild) }
            }
            JsValue.Undefined
        }
        value.properties["querySelector"] = native("querySelector", 1) { _, _, args -> nodeOrNull(page.document.querySelector(stringArg(args, 0), node)) }
        value.properties["querySelectorAll"] = native("querySelectorAll", 1) { _, _, args -> nodes(page.document.querySelectorAll(stringArg(args, 0), node)) }
        value.properties["addEventListener"] = eventTargetAdd(node)
        value.properties["removeEventListener"] = eventTargetRemove(node)
        value.properties["dispatchEvent"] = eventTargetDispatch(node)
    }

    private fun installElementMethods(value: LiveObject, element: ElementNode) {
        value.addGetter("tagName") { JsValue.StringValue(element.nodeName) }
        value.addGetter("localName") { JsValue.StringValue(element.localName) }
        value.addGetter("id") { JsValue.StringValue(element.id.orEmpty()) }
        value.addGetter("className") { JsValue.StringValue(element.getAttribute("class").orEmpty()) }
        value.addGetter("value") { JsValue.StringValue(element.getAttribute("value").orEmpty()) }
        value.addGetter("type") { JsValue.StringValue(element.getAttribute("type").orEmpty()) }
        value.addGetter("name") { JsValue.StringValue(element.getAttribute("name").orEmpty()) }
        value.addGetter("checked") { JsValue.BooleanValue(element.hasAttribute("checked")) }
        value.addGetter("disabled") { JsValue.BooleanValue(element.hasAttribute("disabled")) }
        value.addGetter("hidden") { JsValue.BooleanValue(element.hasAttribute("hidden")) }
        value.addGetter("classList") { classListObject(element) }
        value.addGetter("style") { styleObject(element) }
        value.addGetter("dataset") { datasetObject(element) }
        value.addSetter("id") { _, item -> setAttribute(element, "id", item.displayString()); true }
        value.addSetter("className") { _, item -> setAttribute(element, "class", item.displayString()); true }
        value.addSetter("value") { _, item -> setAttribute(element, "value", item.displayString()); true }
        value.addSetter("checked") { _, item -> toggleBooleanAttribute(element, "checked", item.isTruthy()); true }
        value.addSetter("disabled") { _, item -> toggleBooleanAttribute(element, "disabled", item.isTruthy()); true }
        value.addSetter("hidden") { _, item -> toggleBooleanAttribute(element, "hidden", item.isTruthy()); true }
        value.properties["getAttribute"] = native("getAttribute", 1) { _, _, args -> element.getAttribute(stringArg(args, 0))?.let(JsValue::StringValue) ?: JsValue.Null }
        value.properties["hasAttribute"] = native("hasAttribute", 1) { _, _, args -> JsValue.BooleanValue(element.hasAttribute(stringArg(args, 0))) }
        value.properties["setAttribute"] = native("setAttribute", 2) { _, _, args -> setAttribute(element, stringArg(args, 0), stringArg(args, 1)); JsValue.Undefined }
        value.properties["removeAttribute"] = native("removeAttribute", 1) { _, _, args -> JsValue.BooleanValue(mutateResult { page.document.removeAttribute(element, stringArg(args, 0)) }) }
        value.properties["setInnerHTML"] = native("setInnerHTML", 1) { _, _, args -> mutate { page.document.setInnerHtml(element, stringArg(args, 0)) }; JsValue.Undefined }
        value.properties["matches"] = native("matches", 1) { _, _, args -> JsValue.BooleanValue(matchesSelector(element, stringArg(args, 0))) }
        value.properties["closest"] = native("closest", 1) { _, _, args ->
            val selector = stringArg(args, 0)
            val match = generateSequence(element as DomNode?) { it.parent }.filterIsInstance<ElementNode>().firstOrNull { matchesSelector(it, selector) }
            nodeOrNull(match)
        }
        value.properties["focus"] = native("focus", 0) { _, _, _ -> page.activeElement = element; page.document.dispatchEvent(element, BrowserEvent("focus", bubbles = false)); JsValue.Undefined }
        value.properties["blur"] = native("blur", 0) { _, _, _ -> if (page.activeElement === element) page.activeElement = null; page.document.dispatchEvent(element, BrowserEvent("blur", bubbles = false)); JsValue.Undefined }
        value.properties["click"] = native("click", 0) { _, _, _ -> JsValue.BooleanValue(page.document.dispatchEvent(element, BrowserEvent("click", cancelable = true))) }
    }

    private fun classListObject(element: ElementNode): LiveObject = LiveObject(
        getters = mapOf(
            "length" to { JsValue.NumberValue(element.classNames.size.toDouble()) },
            "value" to { JsValue.StringValue(element.getAttribute("class").orEmpty()) }
        )
    ).also { list ->
        list.properties["contains"] = native("contains", 1) { _, _, args -> JsValue.BooleanValue(stringArg(args, 0) in element.classNames) }
        list.properties["add"] = native("add", 1) { _, _, args ->
            val classes = element.classNames.toMutableSet(); args.map(JsValue::displayString).filter(String::isNotBlank).forEach(classes::add)
            setAttribute(element, "class", classes.joinToString(" ")); JsValue.Undefined
        }
        list.properties["remove"] = native("remove", 1) { _, _, args ->
            val remove = args.map(JsValue::displayString).toSet(); setAttribute(element, "class", element.classNames.filterNot(remove::contains).joinToString(" ")); JsValue.Undefined
        }
        list.properties["toggle"] = native("toggle", 2) { _, _, args ->
            val token = stringArg(args, 0); val force = args.getOrNull(1)?.takeUnless { it === JsValue.Undefined }?.isTruthy()
            val classes = element.classNames.toMutableSet(); val present = token in classes
            val next = force ?: !present
            if (next) classes += token else classes -= token
            setAttribute(element, "class", classes.joinToString(" ")); JsValue.BooleanValue(next)
        }
        list.properties["replace"] = native("replace", 2) { _, _, args ->
            val old = stringArg(args, 0); val replacement = stringArg(args, 1); val classes = element.classNames.toMutableSet()
            val changed = classes.remove(old); if (changed) classes += replacement
            setAttribute(element, "class", classes.joinToString(" ")); JsValue.BooleanValue(changed)
        }
        list.properties["item"] = native("item", 1) { _, _, args -> element.classNames.elementAtOrNull(numberArg(args, 0).toInt())?.let(JsValue::StringValue) ?: JsValue.Null }
    }

    private fun styleObject(element: ElementNode): LiveObject = LiveObject(
        dynamicGetter = { _, name -> inlineStyles(element)[cssPropertyName(name)]?.let(JsValue::StringValue) },
        dynamicSetter = { _, name, value ->
            if (name in setOf("length", "cssText")) false else { updateInlineStyle(element, cssPropertyName(name), value.displayString()); true }
        },
        getters = mapOf(
            "cssText" to { JsValue.StringValue(element.getAttribute("style").orEmpty()) },
            "length" to { JsValue.NumberValue(inlineStyles(element).size.toDouble()) }
        ),
        setters = mapOf(
            "cssText" to { _, value -> setAttribute(element, "style", value.displayString()); true }
        ),
        ownNames = { inlineStyles(element).keys.toList() }
    ).also { style ->
        style.properties["getPropertyValue"] = native("getPropertyValue", 1) { _, _, args -> JsValue.StringValue(inlineStyles(element)[stringArg(args, 0).lowercase(Locale.ROOT)].orEmpty()) }
        style.properties["setProperty"] = native("setProperty", 2) { _, _, args -> updateInlineStyle(element, stringArg(args, 0), stringArg(args, 1)); JsValue.Undefined }
        style.properties["removeProperty"] = native("removeProperty", 1) { _, _, args ->
            val key = stringArg(args, 0).lowercase(Locale.ROOT); val map = inlineStyles(element); val old = map.remove(key).orEmpty(); writeInlineStyles(element, map); JsValue.StringValue(old)
        }
    }

    private fun datasetObject(element: ElementNode): LiveObject = LiveObject(
        dynamicGetter = { _, name -> element.getAttribute("data-${camelToKebab(name)}")?.let(JsValue::StringValue) },
        dynamicSetter = { _, name, value -> setAttribute(element, "data-${camelToKebab(name)}", value.displayString()); true },
        ownNames = { element.attributes.keys.filter { it.startsWith("data-") }.map { kebabToCamel(it.removePrefix("data-")) } }
    )

    private fun computedStyleObject(element: ElementNode?): LiveObject {
        val properties = element?.let(page::computedStyle).orEmpty()
        return LiveObject(
            dynamicGetter = { _, name -> properties[cssPropertyName(name)]?.let(JsValue::StringValue) },
            getters = mapOf("length" to { JsValue.NumberValue(properties.size.toDouble()) }),
            ownNames = { properties.keys.toList() }
        ).also { style ->
            style.properties["getPropertyValue"] = native("getPropertyValue", 1) { _, _, args -> JsValue.StringValue(properties[stringArg(args, 0).lowercase(Locale.ROOT)].orEmpty()) }
        }
    }

    private fun locationObject(): LiveObject = LiveObject(
        getters = mapOf(
            "href" to { JsValue.StringValue(page.url) },
            "origin" to { JsValue.StringValue(page.origin) },
            "protocol" to { JsValue.StringValue(page.url.substringBefore(':') + ":") }
        ),
        setters = mapOf(
            "href" to { _, value -> page.requestNavigation(value.displayString(), replace = false); true }
        )
    ).also { location ->
        location.properties["assign"] = native("assign", 1) { _, _, args -> page.requestNavigation(stringArg(args, 0), replace = false); JsValue.Undefined }
        location.properties["replace"] = native("replace", 1) { _, _, args -> page.requestNavigation(stringArg(args, 0), replace = true); JsValue.Undefined }
        location.properties["reload"] = native("reload", 0) { _, _, _ -> page.requestNavigation(page.url, replace = true); JsValue.Undefined }
        location.properties["toString"] = native("toString", 0) { _, _, _ -> JsValue.StringValue(page.url) }
    }

    private fun eventConstructor(arguments: List<JsValue>): JsValue.ObjectValue {
        val type = stringArg(arguments, 0)
        val options = arguments.getOrNull(1) as? JsValue.ObjectValue
        return LiveObject(getters = mapOf(
            "type" to { JsValue.StringValue(type) },
            "bubbles" to { JsValue.BooleanValue(booleanProperty(options, "bubbles")) },
            "cancelable" to { JsValue.BooleanValue(booleanProperty(options, "cancelable")) },
            "detail" to { options?.properties?.get("detail") ?: JsValue.Undefined }
        ))
    }

    private fun mutationObserverObject(context: JsExecutionContext, arguments: List<JsValue>): JsValue.ObjectValue {
        val callback = arguments.firstOrNull() as? JsValue.FunctionValue
            ?: throw JsRuntimeException("TypeError", "MutationObserver callback must be a function")
        var observer: com.mohnishraj.aether.core.browser.mutation.BrowserMutationObserver? = null
        return LiveObject().also { value ->
            value.properties["observe"] = native("observe", 2) { _, _, args ->
                observer?.let(page.document::disconnect)
                val target = nodeArg(args, 0)
                val options = args.getOrNull(1) as? JsValue.ObjectValue
                observer = page.document.observe(target, mutationOptions(options)) { records ->
                    context.call(callback, value, listOf(mutationRecords(records), value))
                }
                JsValue.Undefined
            }
            value.properties["disconnect"] = native("disconnect", 0) { _, _, _ -> observer?.let(page.document::disconnect); observer = null; JsValue.Undefined }
            value.properties["takeRecords"] = native("takeRecords", 0) { _, _, _ -> JsValue.ArrayValue(observer?.takeRecords()?.let(::mutationRecordValues).orEmpty()) }
        }
    }

    private fun xmlHttpRequestObject(): LiveObject {
        var method = "GET"
        var url = ""
        var async = true
        var status = 0
        var statusText = ""
        var responseText = ""
        var responseUrl = ""
        var readyState = 0
        val headers = linkedMapOf<String, String>()
        lateinit var xhr: LiveObject
        fun invokeHandler(context: JsExecutionContext, name: String) {
            val callback = xhr.properties[name] as? JsValue.FunctionValue ?: return
            context.call(callback, xhr, listOf(eventConstructor(listOf(JsValue.StringValue(name.removePrefix("on"))))))
        }
        xhr = LiveObject(
            getters = mapOf(
                "readyState" to { JsValue.NumberValue(readyState.toDouble()) },
                "status" to { JsValue.NumberValue(status.toDouble()) },
                "statusText" to { JsValue.StringValue(statusText) },
                "responseText" to { JsValue.StringValue(responseText) },
                "response" to { JsValue.StringValue(responseText) },
                "responseURL" to { JsValue.StringValue(responseUrl) }
            )
        )
        xhr.properties["open"] = native("open", 3) { context, _, args ->
            method = stringArg(args, 0).uppercase(Locale.ROOT); url = stringArg(args, 1); async = args.getOrNull(2)?.isTruthy() != false; readyState = 1
            invokeHandler(context, "onreadystatechange"); JsValue.Undefined
        }
        xhr.properties["setRequestHeader"] = native("setRequestHeader", 2) { _, _, args -> headers[stringArg(args, 0)] = stringArg(args, 1); JsValue.Undefined }
        xhr.properties["send"] = native("send", 1) { context, _, args ->
            val requestBody = args.firstOrNull()?.takeUnless { it === JsValue.Null || it === JsValue.Undefined }?.displayString()
            val response = page.fetch.fetch(BrowserFetchRequest(url, method, headers, requestBody), page.url)
            status = response.status; statusText = response.statusText; responseText = response.text(); responseUrl = response.url; readyState = 4
            val task = native("xhrComplete", 0) { taskContext, _, _ -> invokeHandler(taskContext, "onreadystatechange"); invokeHandler(taskContext, "onload"); JsValue.Undefined }
            if (async) context.schedule(task, 0L, emptyList(), microtask = true) else context.call(task, xhr, emptyList())
            JsValue.Undefined
        }
        xhr.properties["abort"] = native("abort", 0) { context, _, _ -> readyState = 0; status = 0; responseText = ""; invokeHandler(context, "onabort"); JsValue.Undefined }
        xhr.properties["getAllResponseHeaders"] = native("getAllResponseHeaders", 0) { _, _, _ -> JsValue.StringValue("") }
        return xhr
    }

    private fun addEventListener(context: JsExecutionContext, target: DomNode, args: List<JsValue>): JsValue {
        val type = stringArg(args, 0)
        val callback = args.getOrNull(1) as? JsValue.FunctionValue ?: throw JsRuntimeException("TypeError", "Event listener must be a function")
        val options = args.getOrNull(2) as? JsValue.ObjectValue
        val handle = page.document.addEventListener(
            target,
            type,
            BrowserEventListener { event -> context.call(callback, node(target), listOf(eventObject(event))) },
            EventListenerOptions(
                capture = booleanProperty(options, "capture"),
                once = booleanProperty(options, "once"),
                passive = booleanProperty(options, "passive")
            )
        )
        return JsValue.NumberValue(handle.id.toDouble())
    }

    private fun setEventHandler(context: JsExecutionContext, target: DomNode, type: String, value: JsValue) {
        val key = target.nodeId to type
        propertyListenerHandles.remove(key)?.let { page.document.removeEventListener(target, it) }
        val callback = value as? JsValue.FunctionValue ?: return
        propertyListenerHandles[key] = page.document.addEventListener(target, type, BrowserEventListener { event ->
            context.call(callback, node(target), listOf(eventObject(event)))
        })
    }

    private fun eventTargetAdd(target: DomNode): JsNativeFunction = native("addEventListener", 3) { context, _, args -> addEventListener(context, target, args) }
    private fun eventTargetRemove(target: DomNode): JsNativeFunction = native("removeEventListener", 2) { _, _, args ->
        val id = args.getOrNull(1)?.toNumber() ?: args.firstOrNull()?.toNumber() ?: 0.0
        JsValue.BooleanValue(page.document.removeEventListener(target, EventListenerHandle(id.toLong())))
    }
    private fun eventTargetDispatch(target: DomNode): JsNativeFunction = native("dispatchEvent", 1) { context, _, args ->
        val candidate = args.firstOrNull()
        val type = when (candidate) {
            is JsValue.ObjectValue -> candidate.getProperty(context, "type")?.displayString().orEmpty()
            else -> candidate?.displayString().orEmpty()
        }
        val detail = if (candidate is JsValue.ObjectValue) objectStringMap(candidate.getProperty(context, "detail")) else objectStringMap(args.getOrNull(1))
        JsValue.BooleanValue(page.document.dispatchEvent(target, BrowserEvent(type, detail = detail)))
    }

    private fun eventObject(event: BrowserEvent): JsValue.ObjectValue = LiveObject(
        getters = mapOf(
            "type" to { JsValue.StringValue(event.type) },
            "target" to { nodeOrNull(event.target) },
            "currentTarget" to { nodeOrNull(event.currentTarget) },
            "eventPhase" to { JsValue.NumberValue(event.eventPhase.ordinal.toDouble()) },
            "defaultPrevented" to { JsValue.BooleanValue(event.defaultPrevented) },
            "bubbles" to { JsValue.BooleanValue(event.bubbles) },
            "cancelable" to { JsValue.BooleanValue(event.cancelable) },
            "detail" to { JsValue.ObjectValue(event.detail.mapValues { JsValue.StringValue(it.value) }) }
        )
    ).also { value ->
        value.properties["preventDefault"] = native("preventDefault", 0) { _, _, _ -> event.preventDefault(); JsValue.Undefined }
        value.properties["stopPropagation"] = native("stopPropagation", 0) { _, _, _ -> event.stopPropagation(); JsValue.Undefined }
        value.properties["stopImmediatePropagation"] = native("stopImmediatePropagation", 0) { _, _, _ -> event.stopImmediatePropagation(); JsValue.Undefined }
    }

    private fun storageObject(area: BrowserStorageArea, name: String): LiveObject = LiveObject(
        getters = mapOf("length" to { JsValue.NumberValue(area.length.toDouble()) }, "name" to { JsValue.StringValue(name) }),
        dynamicGetter = { _, key -> area.getItem(key)?.let(JsValue::StringValue) },
        dynamicSetter = { _, key, value -> area.setItem(key, value.displayString()); true },
        ownNames = { (0 until area.length).mapNotNull(area::key) }
    ).also { value ->
        value.properties["key"] = native("key", 1) { _, _, args -> area.key(numberArg(args, 0).toInt())?.let(JsValue::StringValue) ?: JsValue.Null }
        value.properties["getItem"] = native("getItem", 1) { _, _, args -> area.getItem(stringArg(args, 0))?.let(JsValue::StringValue) ?: JsValue.Null }
        value.properties["setItem"] = native("setItem", 2) { _, _, args -> area.setItem(stringArg(args, 0), stringArg(args, 1)); JsValue.Undefined }
        value.properties["removeItem"] = native("removeItem", 1) { _, _, args -> area.removeItem(stringArg(args, 0)); JsValue.Undefined }
        value.properties["clear"] = native("clear", 0) { _, _, _ -> area.clear(); JsValue.Undefined }
    }

    private fun clipboardObject(): JsValue.ObjectValue = JsValue.ObjectValue().also { value ->
        value.properties["readText"] = native("readText", 0) { _, _, _ ->
            val decision = page.authorizePermission(PermissionFeature.CLIPBOARD_READ)
            if (!decision.allowed) throw JsRuntimeException("SecurityError", decision.reason)
            counters.clipboardOperations.incrementAndGet()
            JsPromiseValue.fulfilled(JsValue.StringValue(clipboard.readText()))
        }
        value.properties["writeText"] = native("writeText", 1) { _, _, args ->
            val decision = page.authorizePermission(PermissionFeature.CLIPBOARD_WRITE)
            if (!decision.allowed) throw JsRuntimeException("SecurityError", decision.reason)
            val text = stringArg(args, 0)
            require(text.length <= limits.maxClipboardChars) { "Clipboard text exceeds ${limits.maxClipboardChars} characters" }
            clipboard.writeText(text)
            counters.clipboardOperations.incrementAndGet()
            JsPromiseValue.fulfilled(JsValue.Undefined)
        }
    }

    private fun fetchResponse(arguments: List<JsValue>, promiseBody: Boolean): JsValue.ObjectValue {
        val url = stringArg(arguments, 0)
        val options = arguments.getOrNull(1) as? JsValue.ObjectValue
        val headers = (options?.properties?.get("headers") as? JsValue.ObjectValue)?.properties.orEmpty().mapValues { it.value.displayString() }
        val request = BrowserFetchRequest(
            url = url,
            method = stringProperty(options, "method") ?: "GET",
            headers = headers,
            body = stringProperty(options, "body"),
            cache = stringProperty(options, "cache") ?: "default",
            redirect = stringProperty(options, "redirect") ?: "follow",
            maxResponseBytes = numberProperty(options, "maxResponseBytes")?.toLong()
        )
        val response = page.fetch.fetch(request, page.url)
        return LiveObject(getters = mapOf(
            "url" to { JsValue.StringValue(response.url) },
            "status" to { JsValue.NumberValue(response.status.toDouble()) },
            "statusText" to { JsValue.StringValue(response.statusText) },
            "ok" to { JsValue.BooleanValue(response.ok) },
            "redirected" to { JsValue.BooleanValue(response.redirected) },
            "type" to { JsValue.StringValue("basic") },
            "fromCache" to { JsValue.BooleanValue(response.fromCache) },
            "headers" to { JsValue.ObjectValue(response.headers.mapValues { JsValue.ArrayValue(it.value.map(JsValue::StringValue)) }) }
        )).also { objectValue ->
            objectValue.properties["text"] = native("text", 0) { _, _, _ ->
                val text = JsValue.StringValue(response.text())
                if (promiseBody) JsPromiseValue.fulfilled(text) else text
            }
            objectValue.properties["textSync"] = native("textSync", 0) { _, _, _ -> JsValue.StringValue(response.text()) }
            objectValue.properties["json"] = native("json", 0) { _, _, _ ->
                val parsed = JsJson.parse(response.text(), page.jsLimits)
                if (promiseBody) JsPromiseValue.fulfilled(parsed) else parsed
            }
        }
    }

    private fun observeMutations(context: JsExecutionContext, arguments: List<JsValue>): JsValue {
        val target = nodeArg(arguments, 0)
        val callback = arguments.getOrNull(1) as? JsValue.FunctionValue ?: throw JsRuntimeException("TypeError", "Mutation callback must be a function")
        val options = arguments.getOrNull(2) as? JsValue.ObjectValue
        val observer = page.document.observe(target, mutationOptions(options)) { records ->
            context.call(callback, JsValue.Undefined, listOf(mutationRecords(records)))
        }
        return JsValue.ObjectValue(mapOf(
            "id" to JsValue.NumberValue(observer.id.toDouble()),
            "disconnect" to native("disconnect", 0) { _, _, _ -> page.document.mutations.disconnect(observer); JsValue.Undefined },
            "takeRecords" to native("takeRecords", 0) { _, _, _ -> mutationRecords(observer.takeRecords()) }
        ))
    }

    private fun mutationOptions(options: JsValue.ObjectValue?): MutationObserverOptions = MutationObserverOptions(
        childList = booleanProperty(options, "childList", default = true),
        attributes = booleanProperty(options, "attributes", default = true),
        characterData = booleanProperty(options, "characterData", default = true),
        subtree = booleanProperty(options, "subtree"),
        attributeOldValue = booleanProperty(options, "attributeOldValue"),
        characterDataOldValue = booleanProperty(options, "characterDataOldValue")
    )

    private fun mutationRecords(records: List<com.mohnishraj.aether.core.browser.mutation.MutationRecord>): JsValue.ArrayValue =
        JsValue.ArrayValue(mutationRecordValues(records))

    private fun mutationRecordValues(records: List<com.mohnishraj.aether.core.browser.mutation.MutationRecord>): List<JsValue> = records.map { record ->
        JsValue.ObjectValue(mapOf(
            "type" to JsValue.StringValue(record.type.name.lowercase(Locale.ROOT)),
            "target" to node(record.target),
            "attributeName" to (record.attributeName?.let(JsValue::StringValue) ?: JsValue.Null),
            "oldValue" to (record.oldValue?.let(JsValue::StringValue) ?: JsValue.Null),
            "addedNodes" to nodes(record.addedNodes),
            "removedNodes" to nodes(record.removedNodes)
        ))
    }

    private fun serializeForm(arguments: List<JsValue>): JsValue {
        val form = nodeArg(arguments, 0) as? ElementNode ?: throw JsRuntimeException("TypeError", "Expected a form element")
        val submission = page.forms.serialize(form, page.url)
        val authorization = page.authorizeForm(submission.action)
        if (!authorization.allowed) throw JsRuntimeException("SecurityError", authorization.reason)
        return JsValue.ObjectValue(mapOf(
            "method" to JsValue.StringValue(submission.method),
            "action" to JsValue.StringValue(submission.action),
            "enctype" to JsValue.StringValue(submission.enctype),
            "body" to JsValue.StringValue(submission.encodedBody),
            "valid" to JsValue.BooleanValue(submission.validation.valid),
            "fields" to JsValue.ArrayValue(submission.fields.map { field -> JsValue.ObjectValue(mapOf("name" to JsValue.StringValue(field.name), "value" to JsValue.StringValue(field.value))) }),
            "issues" to JsValue.ArrayValue(submission.validation.issues.map { issue -> JsValue.StringValue(issue.message) })
        ))
    }

    private fun matchesSelector(element: ElementNode, selector: String): Boolean = runCatching {
        page.document.querySelectorAll(selector, element.root()).any { it === element }
    }.getOrDefault(false)

    private fun setAttribute(element: ElementNode, name: String, value: String) = mutate { page.document.setAttribute(element, name, value) }
    private fun toggleBooleanAttribute(element: ElementNode, name: String, enabled: Boolean) {
        if (enabled) setAttribute(element, name, "") else mutate { page.document.removeAttribute(element, name) }
    }

    private fun inlineStyles(element: ElementNode): LinkedHashMap<String, String> {
        val output = linkedMapOf<String, String>()
        element.getAttribute("style").orEmpty().split(';').forEach { declaration ->
            val split = declaration.indexOf(':')
            if (split > 0) output[declaration.substring(0, split).trim().lowercase(Locale.ROOT)] = declaration.substring(split + 1).trim()
        }
        return output
    }

    private fun updateInlineStyle(element: ElementNode, property: String, value: String) {
        val styles = inlineStyles(element)
        if (value.isBlank()) styles.remove(property.lowercase(Locale.ROOT)) else styles[property.lowercase(Locale.ROOT)] = value
        writeInlineStyles(element, styles)
    }

    private fun writeInlineStyles(element: ElementNode, styles: Map<String, String>) = setAttribute(
        element,
        "style",
        styles.entries.joinToString(";") { (name, value) -> "$name:$value" }
    )

    private fun cssPropertyName(name: String): String = if ('-' in name) name.lowercase(Locale.ROOT) else camelToKebab(name)
    private fun camelToKebab(value: String): String = value.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase(Locale.ROOT)
    private fun kebabToCamel(value: String): String = value.split('-').let { parts -> parts.firstOrNull().orEmpty() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } }

    private fun mutate(block: () -> Unit) { block(); page.document.deliverMutations() }
    private fun <T> mutateResult(block: () -> T): T = block().also { page.document.deliverMutations() }
    private fun nodeOrNull(node: DomNode?): JsValue = node?.let(::node) ?: JsValue.Null
    private fun nodes(values: List<DomNode>): JsValue.ArrayValue = JsValue.ArrayValue(values.map(::node))
    private fun nodeArg(arguments: List<JsValue>, index: Int): DomNode = nodeFromValue(arguments.getOrNull(index))
    private fun nodeFromValue(value: JsValue?): DomNode = (value as? JsValue.ObjectValue)?.let(objectToNode::get) ?: throw JsRuntimeException("TypeError", "Expected a DOM node")
    private fun stringArg(arguments: List<JsValue>, index: Int): String = arguments.getOrNull(index)?.displayString() ?: throw JsRuntimeException("TypeError", "Missing argument ${index + 1}")
    private fun numberArg(arguments: List<JsValue>, index: Int): Double = arguments.getOrNull(index)?.toNumber() ?: throw JsRuntimeException("TypeError", "Missing argument ${index + 1}")
    private fun booleanProperty(value: JsValue.ObjectValue?, name: String, default: Boolean = false): Boolean = value?.properties?.get(name)?.isTruthy() ?: default
    private fun stringProperty(value: JsValue.ObjectValue?, name: String): String? = value?.properties?.get(name)?.takeUnless { it === JsValue.Undefined || it === JsValue.Null }?.displayString()
    private fun numberProperty(value: JsValue.ObjectValue?, name: String): Double? = value?.properties?.get(name)?.takeUnless { it === JsValue.Undefined || it === JsValue.Null }?.toNumber()
    private fun objectStringMap(value: JsValue?): Map<String, String> = (value as? JsValue.ObjectValue)?.properties.orEmpty().mapValues { it.value.displayString() }
    private fun domNodeTypeNumber(type: DomNodeType): Int = when (type) {
        DomNodeType.ELEMENT -> 1
        DomNodeType.TEXT -> 3
        DomNodeType.COMMENT -> 8
        DomNodeType.DOCUMENT -> 9
        DomNodeType.DOCUMENT_TYPE -> 10
        DomNodeType.DOCUMENT_FRAGMENT -> 11
    }

    private fun native(name: String, length: Int, block: (JsExecutionContext, JsValue, List<JsValue>) -> JsValue): JsNativeFunction =
        JsNativeFunction(name, length, block)

    private class LiveObject(
        getters: Map<String, () -> JsValue> = emptyMap(),
        setters: Map<String, (JsExecutionContext, JsValue) -> Boolean> = emptyMap(),
        private val dynamicGetter: ((JsExecutionContext, String) -> JsValue?)? = null,
        private val dynamicSetter: ((JsExecutionContext, String, JsValue) -> Boolean)? = null,
        private val ownNames: (() -> List<String>)? = null
    ) : JsValue.ObjectValue() {
        private val getters = linkedMapOf<String, () -> JsValue>().apply { putAll(getters) }
        private val setters = linkedMapOf<String, (JsExecutionContext, JsValue) -> Boolean>().apply { putAll(setters) }

        fun addGetter(name: String, getter: () -> JsValue) { getters[name] = getter }
        fun addSetter(name: String, setter: (JsExecutionContext, JsValue) -> Boolean) { setters[name] = setter }

        override fun getProperty(context: JsExecutionContext, name: String): JsValue? =
            getters[name]?.invoke() ?: super.getProperty(context, name) ?: dynamicGetter?.invoke(context, name)

        override fun setProperty(context: JsExecutionContext, name: String, value: JsValue): Boolean {
            setters[name]?.let { return it(context, value) }
            if (dynamicSetter?.invoke(context, name, value) == true) return true
            return super.setProperty(context, name, value)
        }

        override fun ownPropertyNames(): List<String> = (super.ownPropertyNames() + getters.keys + (ownNames?.invoke().orEmpty())).distinct()
    }
}
