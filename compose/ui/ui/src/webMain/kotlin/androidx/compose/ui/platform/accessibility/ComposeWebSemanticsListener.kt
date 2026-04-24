/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(
    ExperimentalComposeUiApi::class,
    InternalComposeUiApi::class,
    ExperimentalWasmJsInterop::class,
)


package androidx.compose.ui.platform.accessibility

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsNumber
import kotlin.js.definedExternally
import kotlin.js.js
import kotlin.js.toDouble
import kotlin.js.toJsNumber
import kotlin.js.unsafeCast
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import org.w3c.dom.AddEventListenerOptions
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLProgressElement
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener as EventListenerInterface
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.InputEvent
import org.w3c.dom.events.InputEventInit
import org.w3c.dom.events.KeyboardEvent

internal class ComposeWebSemanticsListener(
    val coroutineScope: CoroutineScope,
    val webSemanticsRoot: HTMLElement,
) : PlatformContext.SemanticsOwnerListener {
    private val owners = mutableSetOf<SemanticsOwner>()

    private val canvas =
        webSemanticsRoot.previousElementSibling?.previousElementSibling as? HTMLCanvasElement
    private val backingDomDiv = webSemanticsRoot.previousElementSibling as? HTMLDivElement

    private val syncFlow =
        MutableSharedFlow<Unit>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    private val eventHandlerCaller = canvas?.let(::EventHandlerCaller)

    private var preventFocus = false

    init {

        // every browser other than Chrome needs this attribute in order for copy/paste events to be sendable to the
        // canvas. In Chrome however setting this results in copy/paste no longer working.
        if (!isChrome())
            canvas?.setAttribute("contenteditable", "true")

        webSemanticsRoot.removeAttribute("aria-live")
        webSemanticsRoot.setAttribute("role", "application")


        backingDomDiv?.addEventListener(
            "keydown",
            { event ->
                if (event is KeyboardEvent) {
                    // We prevent focus right before a copy, paste or cut event to prevent it from being send to
                    // the wrong HtmlElement (The Shadow Element) and thus being ignored.
                    if (isClipboardEventTrigger(event)) {
                        preventFocus = true
                    }
                }
            },
            AddEventListenerOptions(capture = true),
        )

        webSemanticsRoot.addEventListener(
            "keydown",
            { event ->
                // we need to prevent the default (moving focus) on these keys because we handle it ourselves
                if (event is KeyboardEvent && listOf(
                        "ArrowLeft",
                        "ArrowRight",
                        "ArrowDown",
                        "ArrowUp",
                        "Tab"
                    ).contains(event.key)
                ) event.preventDefault()

                val backingInputField =
                    backingDomDiv?.querySelector("input, textarea") as? HTMLElement
                // If the backingInputField exists and we send keydown events to the canvas,
                // they might get processed in the wrong order, because the canvas's EventHandler does not care about event.timeStamp
                if (backingInputField == null) {
                    eventHandlerCaller?.callWithEvent(event)
                } else {
                    if (event is KeyboardEvent) {
                        // We prevent focus right before a copy, paste or cut event to prevent it from being send to the wrong HtmlElement (The Shadow Element)
                        // and thus being ignored. Redispatching doesn't work because of the isTrusted flag (it might honestly also be other browser code magic)
                        if (isClipboardEventTrigger(event)) {
                            preventFocus = true
                            backingInputField.focus()
                        }

                        // If there is a backing textarea or input field, then compose always intends for keydown and keyup events to go to it.
                        // Redispatching them works, because we are only interested in triggering
                        // the custom Event Handler in androidx.compose.ui.platform.DomInputStrategy
                        backingInputField.dispatchEvent(
                            copyKeyboardEvent(event).also {
                                setEventTimestamp(
                                    it,
                                    event.timeStamp.toDouble().toJsNumber()
                                )
                            })
                    }
                }
            },
            AddEventListenerOptions(capture = true),
        )

        webSemanticsRoot.addEventListener(
            "keyup",
            { event ->
                val backingInputField =
                    backingDomDiv?.querySelector("input, textarea") as? HTMLElement
                if (backingInputField == null) {
                    eventHandlerCaller?.callWithEvent(event)
                } else {
                    if (event is KeyboardEvent) {
                        backingInputField.dispatchEvent(
                            copyKeyboardEvent(event).also {
                                setEventTimestamp(
                                    it,
                                    event.timeStamp.toDouble().toJsNumber()
                                )
                            })
                    }
                }
            },
            AddEventListenerOptions(capture = true),
        )

        // We never have to send copy, paste and cut events to the canvas, as there are no listeners for them
        //for (type in listOf("copy", "paste", "cut")) webSemanticsRoot.addEventListener(
        //    type,
        //    EventListener { event ->
        //        // Do nothing
        //    },
        //    AddEventListenerOptions(capture = true),)

        for (type in listOf("copy", "paste", "cut")) document.addEventListener(
            type,
            { _ ->
                // Once a copy, paste or cut event has been send, we no longer need to prevent focus
                preventFocus = false
            },
            AddEventListenerOptions(capture = false)
        ) //Needs to only trigger when bubbling back up

        webSemanticsRoot.addEventListener(
            "beforeinput",
            { event ->
                event.preventDefault()
                if (event is InputEvent) {
                    // Redispatching beforeinput events works despite the new event being not trusted,
                    // because we are only interested in triggering the custom Event Handler in androidx.compose.ui.platform.DomInputStrategy
                    (backingDomDiv?.querySelector("input, textarea") as? HTMLElement)?.dispatchEvent(
                        copyInputEvent(event).also {
                            setEventTimestamp(it, event.timeStamp.toDouble().toJsNumber())
                        }
                    )
                }
            },
            AddEventListenerOptions(capture = true),
        )

        coroutineScope.launch {
            syncFlow
                .conflate()
                .collect {
                    for (owner in owners) {
                        onSemanticsChangeInner(owner)
                    }
                }
        }

        coroutineScope.launch {
            while (true) {
                syncFlow.emit(Unit)
                delay(100.milliseconds)
            }
        }
    }

    internal val SemanticsOwner.semanticId: String get() = "cmp-semantic-${rootSemanticsNode.id}"
    internal val SemanticsNode.semanticId: String get() = "cmp-semantic-$id"

    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
        if (findElement(semanticsOwner) != null) return

        val ownerElement = document.createElement("div") as HTMLDivElement

        ownerElement.setAttribute("id", semanticsOwner.semanticId)

        webSemanticsRoot.appendChild(ownerElement)
        owners.add(semanticsOwner)
    }

    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
        val element = checkNotNull(findElement(semanticsOwner)) { "owner does not exist" }

        element.remove()
        owners.remove(semanticsOwner)
    }

    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
        syncFlow.tryEmit(Unit)
    }

    fun onSemanticsChangeInner(semanticsOwner: SemanticsOwner) {
        val queue = ArrayDeque(listOf(semanticsOwner.rootSemanticsNode))
        val parent = findElement(semanticsOwner) ?: return
        val currentIds = collectIds(parent)
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            when (val found = findElement(node)) {
                null -> {
                    // the node does not exist we need to create a new one
                    if (node.config.getOrNull(SemanticsProperties.HideFromAccessibility) != null)
                        continue

                    val el = basicHTMLElement(node)
                    setAttrs(el, node)

                    val parentElement = node.parent?.let(::findElement) ?: webSemanticsRoot
                    val nextElement = node.parent?.let {
                        val index =
                            it.replacedChildren.indexOf(node).takeIf { it >= 0 } ?: return@let null
                        it.replacedChildren.getOrNull(index + 1)?.let(::findElement)
                    }

                    if (nextElement != null) {
                        parentElement.insertBefore(el, nextElement)
                    } else {
                        parentElement.appendChild(el)
                    }
                }

                else -> {
                    if (node.config.getOrNull(SemanticsProperties.HideFromAccessibility) != null)
                        continue

                    // the node does exist, however on the first render the node typically does not have a role
                    // so on the first render we put in a div and later on need to replace it with the correct element.
                    val el = basicHTMLElement(node)
                    if (found.tagName != el.tagName) {
                        setAttrs(el, node)
                        found.replaceWith(el)
                    } else {
                        setAttrs(found, node)
                    }
                }
            }

            seen.add(node.semanticId)
            queue.addAll(node.replacedChildren)
        }

        val unseen = currentIds - seen

        for (id in unseen) {
            findElement(id)?.remove()
        }
    }

    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {}

    private fun findElement(
        owner: SemanticsOwner,
        parent: HTMLElement = webSemanticsRoot
    ): HTMLElement? =
        findElement(owner.semanticId, parent)

    private fun findElement(
        node: SemanticsNode,
        parent: HTMLElement = webSemanticsRoot
    ): HTMLElement? =
        findElement(node.semanticId, parent)

    private fun findElement(
        semanticsId: String,
        parent: HTMLElement = webSemanticsRoot
    ): HTMLElement? =
        parent.querySelector("[id='$semanticsId']") as HTMLElement?

    private fun collectIds(parent: HTMLElement): Set<String> {
        return parent.querySelectorAll("[id]")
            .asSequence()
            .map { it as HTMLElement }
            .map { it.getAttribute("id") }
            .filterNotNull()
            .toSet()
    }

    private fun basicHTMLElement(node: SemanticsNode): HTMLElement {
        return document.createElement(
            when (node.config.getOrNull(SemanticsProperties.Role)) {
                Role.Button -> "button"
                Role.Checkbox -> "input"
                Role.Switch -> "button"
                Role.RadioButton -> "input"
                Role.Tab -> "div"
                Role.Image -> "div"
                Role.DropdownList -> when (node.config.getOrNull(SemanticsProperties.IsEditable)) {
                    true -> "input"
                    else -> "button"
                }

                Role.ValuePicker -> "div"
                Role.Carousel -> "div"
                else -> {
                    when {
                        node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null ->
                            "progress"

                        node.config.getOrNull(SemanticsProperties.IsEditable) != null ->
                            "input"

                        else -> "div"
                    }
                }
            }
        ) as HTMLElement
    }

    private fun setAttrs(el: HTMLElement, node: SemanticsNode) {
        fun <T> setIf(attr: String, prop: SemanticsPropertyKey<T>, value: (T) -> String?) =
            node.config.getOrNull(prop)?.let {
                val v = value(it) ?: return@let null
                if (el.getAttribute(attr) != v)
                    el.setAttribute(attr, v)
            }

        fun setIf(attr: String, prop: SemanticsPropertyKey<String>) = setIf(attr, prop) { it }

        fun <T> doIf(prop: SemanticsPropertyKey<T>, value: (T) -> Unit) =
            node.config.getOrNull(prop)?.let { value(it) }

        el.setAttribute("id", node.semanticId)

        el.style.position = "fixed"
        el.style.whiteSpace = "pre"

        val rootPosition = webSemanticsRoot.getBoundingClientRect().let {
            Offset(it.left.toFloat(), it.top.toFloat())
        }

        val density = node.layoutInfo.density.density
        val toRoot = node.layoutInfo.coordinates.localToRoot(rootPosition).div(density)
        val size = node.boundsInRoot.size.div(density)

        el.style.left = "${toRoot.x}px"
        el.style.top = "${toRoot.y}px"
        el.style.width = "${size.width}px"
        el.style.height = "${size.height}px"

        setIf("data-test-tag", SemanticsProperties.TestTag)

        when (node.config.getOrNull(SemanticsProperties.Role)) {
            Role.DropdownList -> {
                // https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Roles/combobox_role
                el.setAttribute("role", "combobox")
                setIf("type", SemanticsProperties.IsEditable) { // text field
                    if (it) "text" else null
                }
                setIf("aria-expanded", SemanticsActions.Expand) { "false" }
                setIf("aria-expanded", SemanticsActions.Collapse) { "true" }
            }

            Role.RadioButton -> {
                el.setAttribute("type", "radio")
                setIf("aria-label", SemanticsProperties.Text) { it.joinToString() }
                doIf(SemanticsProperties.Selected) { el.checked = it }
            }

            Role.Checkbox -> {
                require(el is HTMLInputElement) { "Role.Checkbox is not HTMLInputElement" }
                el.setAttribute("type", "checkbox")
                setIf("aria-label", SemanticsProperties.Text) { it.joinToString() }
                doIf(SemanticsProperties.Selected) { el.checked = it }
                doIf(SemanticsProperties.ToggleableState) { el.checked = it == ToggleableState.On }
            }

            Role.Button -> {
                doIf(SemanticsProperties.Text) {
                    val text = it.joinToString()
                    if (el.innerText != text) el.innerText = text
                }
                setIf("aria-expanded", SemanticsActions.Expand) { "false" }
                setIf("aria-expanded", SemanticsActions.Collapse) { "true" }
            }

            Role.Switch -> {
                // https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Roles/switch_role
                require(el is HTMLButtonElement) { "Role.Switch is not HTMLButtonElement" }
                el.setAttribute("role", "switch")
                setIf("aria-label", SemanticsProperties.Text) { it.joinToString() }
                setIf("aria-checked", SemanticsProperties.Selected) { it.toString() }
                setIf("aria-checked", SemanticsProperties.ToggleableState) {
                    when (it) {
                        ToggleableState.On -> "true"
                        ToggleableState.Off -> "false"
                        ToggleableState.Indeterminate -> "false"
                    }
                }
            }

            else -> {
                // ThemedAdaptiveDialog sets this paneTitle
                val isDialog = node.config.getOrNull(SemanticsProperties.IsDialog) != null ||
                    node.config.getOrNull(SemanticsProperties.PaneTitle) == "Dialog"
                if (isDialog) {
                    el.setAttribute("role", "dialog")
                    // find a header node and mark it as the label and mark its sibling (if it exists) as the description
                    var hasHeadingAsChild: SemanticsNode? = node
                    while (hasHeadingAsChild != null) {
                        if (hasHeadingAsChild.children.getOrNull(0)?.config?.getOrNull(
                                SemanticsProperties.Heading
                            ) != null
                        )
                            break
                        hasHeadingAsChild = hasHeadingAsChild.children.getOrNull(0)
                    }
                    if (hasHeadingAsChild != null) {
                        hasHeadingAsChild.children.getOrNull(0)?.let {
                            el.setAttribute("aria-labelledby", it.semanticId)
                        }
                        hasHeadingAsChild.children.getOrNull(1)?.let {
                            el.setAttribute("aria-describedby", it.semanticId)
                        }
                    }
                }

                setIf("aria-label", SemanticsProperties.Text, { it.joinToString() })
                // https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Roles/textbox_role
                // https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/input/text

                if (node.config.getOrNull(SemanticsProperties.IsEditable) != null && el is HTMLInputElement) {
                    el.setAttribute("type", "text")
                    setIf("aria-description", SemanticsProperties.InputText) { it.toString() }
                    el.removeAttribute("readonly")
                    if (node.config.getOrNull(SemanticsActions.SetText) == null)
                        el.setAttribute("readonly", "")
                }
            }
        }

        node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.let {
            require(
                el is HTMLProgressElement,
                { "node with ProgressBarRangeInfo is not HTMLProgressElement" })
            el.setAttribute("value", it.current.toString())
            el.setAttribute("max", it.range.endInclusive.toString())
        }

        fun areAllChildrenRadioButtons(node: SemanticsNode): Boolean {
            val innerStack = ArrayDeque(listOf(node))

            var hasRadioChild = false

            while (innerStack.isNotEmpty()) {
                val current = innerStack.removeFirst()

                val role = current.config.getOrNull(SemanticsProperties.Role)

                if (role != null && role != Role.RadioButton) return false
                if (role == Role.RadioButton) hasRadioChild = true

                innerStack.addAll(current.replacedChildren)
            }

            return hasRadioChild
        }

        setIf("role", SemanticsProperties.CollectionInfo) {
            if (areAllChildrenRadioButtons(node)) "radiogroup" else null
        }

        val clickable = node.config.getOrNull(SemanticsActions.OnClick) != null
        if (clickable) {
            if (el.clickListener == null) {
                el.clickListener = EventListener {
                    doIf(SemanticsActions.OnClick) { it.action?.invoke() }
                }
            }
        } else {
            el.clickListener = null
        }

        // TODO: Logic
        // When either RequestFocus or Focused is set, the shadow dom element has to be focusable (e.g. via tabindex or similar)
        // On focus, we have to actually focus the shadow dom element for the screen reader to actually read the text
        // For this to properly work with the handlers from compose, we have to propagate keyboard events, the actual focus
        // event and click events back to the canvas or to the explicit handlers, if they are given.
        val focusable = node.config.getOrNull(SemanticsProperties.Focused) != null
            || node.config.getOrNull(SemanticsActions.RequestFocus) != null
        if (focusable) {
            if (el.focusListener == null) {
                val focusListener = EventListener {
                    doIf(SemanticsActions.RequestFocus) { it.action?.invoke() }
                }

                if (el is HTMLDivElement)
                    el.setAttribute("tabindex", "-1")

                el.focusListener = focusListener
            }
        } else {
            el.removeAttribute("tabindex")
            el.focusListener = null
        }

        doIf(SemanticsProperties.Focused) {
            if (it) {
                if (!preventFocus) {
                    // It is not enough for textboxes to just have focus they also need to be clicked.
                    // This is the same workaround as upstream.
                    doIf(SemanticsProperties.EditableText) { el.click() }
                    el.focus()
                }
            }
        }

        el.removeAttribute("aria-live")
        setIf("aria-live", SemanticsProperties.LiveRegion) {
            when (it) {
                LiveRegionMode.Polite -> "polite"
                LiveRegionMode.Assertive -> "assertive"
                else -> "off"
            }
        }

        setIf("aria-description", SemanticsProperties.ContentDescription) { it.joinToString() }

        val title = node.config.getOrNull(SemanticsProperties.PaneTitle)
        if (title != null) {
            el.setAttribute("title", title)
            if (title == "tooltip")
                el.setAttribute("role", "tooltip")
        }
    }
}

private fun NodeList.asSequence(): Sequence<Node> = object : Sequence<Node> {
    override fun iterator(): Iterator<Node> = object : Iterator<Node> {
        var index = 0

        override fun next(): Node = checkNotNull(item(index)).unsafeCast<Node>().also { index++ }
        override fun hasNext(): Boolean = index < length

    }
}

private external interface FocusListenerElement : JsAny {
    var focusListener: EventListenerInterface?
}

private var HTMLElement.focusListener: EventListenerInterface?
    get() = unsafeCast<FocusListenerElement>().focusListener.takeIf { it != undefined }
    set(value) {
        val self = unsafeCast<FocusListenerElement>()

        self.focusListener?.also {
            self.focusListener = undefined.unsafeCast<EventListenerInterface>()
            removeEventListener("focus", it)
        }

        value?.also {
            self.focusListener = it
            addEventListener("focus", it)
        }
    }

private external interface ClickListenerElement : JsAny {
    var clickListener: EventListenerInterface?
}

private var HTMLElement.clickListener: EventListenerInterface?
    get() = unsafeCast<ClickListenerElement>().clickListener.takeIf { it != undefined }
    set(value) {
        val self = unsafeCast<ClickListenerElement>()

        self.clickListener?.also {
            self.clickListener = undefined.unsafeCast<EventListenerInterface>()
            removeEventListener("click", it)
        }

        value?.also {
            self.clickListener = it
            addEventListener("click", it)
        }
    }

private external interface Checked : JsAny {
    var checked: Boolean
}

private var HTMLElement.checked: Boolean
    get() = unsafeCast<Checked>().checked
    set(value) {
        unsafeCast<Checked>().checked = value
    }

//typealias AnyEventHandler = EventHandler<*, *, *>

private external interface EventTargetExtWrite : JsAny {
    var addEventListener: (type: String, listener: EventListenerInterface, options: AddEventListenerOptions?) -> Unit
    var removeEventListener: (type: String, listener: EventListenerInterface, options: AddEventListenerOptions?) -> Unit
}

private external interface EventTargetExtRead<T : EventTarget> : JsAny {
    var addEventListener: EventTargetCallback<T>
    var removeEventListener: EventTargetCallback<T>
}

private external interface EventTargetCallback<T : EventTarget> {
    fun call(
        self: T,
        type: String,
        listener: EventListenerInterface,
        options: AddEventListenerOptions? = definedExternally
    )
}

private data class EventListenerInfo(
    val type: String,
    val listener: EventListenerInterface,
    val options: AddEventListenerOptions?,
)

private fun interface EventHandlerCaller {
    fun callWithEvent(event: Event)
}

private fun EventHandlerCaller(canvas: HTMLCanvasElement): EventHandlerCaller {
    // In order for the canvas to accept copy, paste, and other native events, they must be marked as trusted.
    // When we manually re-dispatch events, they lose their trusted status (isTrusted = false) and stop working.
    // As a workaround, we intercept calls to addEventListener and removeEventListener on the canvas,
    // maintain our own collection of listeners, and invoke them directly so the original trusted events remain intact.

    val map: MutableMap<EventListenerInterface, EventListenerInfo> =
        mutableMapOf<EventListenerInterface, EventListenerInfo>()
    val eventTarget = canvas.unsafeCast<EventTargetExtRead<HTMLCanvasElement>>()
    val eventTargetWrite = canvas.unsafeCast<EventTargetExtWrite>()

    val originalAddEventListener = eventTarget.addEventListener
    eventTargetWrite.addEventListener = { type, listener, options ->
        map[listener] = EventListenerInfo(type, listener, options)
        originalAddEventListener.call(canvas, type, listener, options)
    }

    val originalRemoveEventHandler = eventTarget.removeEventListener
    eventTargetWrite.removeEventListener = { type, listener, options ->
        map.remove(listener)
        originalRemoveEventHandler.call(canvas, type, listener, options)
    }

    return EventHandlerCaller { event ->
        for (info in map.values) {
            if (info.type == event.type) {
                if (handleEventInListener(info.listener)) {
                    callHandleEvent(info.listener, event)
                } else {
                    call(info.listener, event)
                }
            }
        }
    }
}

private fun handleEventInListener(listener: EventListenerInterface): Boolean =
    js(""""handleEvent" in listener""")

private fun callHandleEvent(listener: EventListenerInterface, event: Event): Unit =
    js("""listener.handleEvent(event)""")

private fun call(listener: EventListenerInterface, event: Event): Unit = js("""listener(event)""")


private fun isChrome(): Boolean = js("""typeof window.chrome !== "undefined"""")

private fun setEventTimestamp(event: Event, timeStamp: JsNumber) {
    js(
        """try {
            Object.defineProperty(event, 'timeStamp', {
                value: timeStamp
            });
        } catch (err) {
            // ignore
        }"""
    )
}

private fun copyInputEvent(event: InputEvent): InputEvent = InputEvent(
    event.type,
    //TODO inputType ?
    InputEventInit(
        data = event.data,
        bubbles = true,
        cancelable = true,
    )
)

private fun copyKeyboardEvent(event: KeyboardEvent): KeyboardEvent =
    js(
        """new KeyboardEvent(event.type, {
            key: event.key,
            code: event.code,
            location: event.location,
            ctrlKey: event.ctrlKey,
            shiftKey: event.shiftKey,
            altKey: event.altKey,
            metaKey: event.metaKey,
            repeat: event.repeat,
            isComposing: event.isComposing,
            bubbles: true,
            cancelable: true
        })"""
    )

private fun isClipboardEventTrigger(event: KeyboardEvent) =
    (event.metaKey || event.ctrlKey) && (event.key == "c" || event.key == "v" || event.key == "x")

private fun EventListener(handler: (Event) -> Unit): EventListenerInterface =
    js("(event) => { handler(event) }")

private val undefined: JsAny = js("""undefined""")
