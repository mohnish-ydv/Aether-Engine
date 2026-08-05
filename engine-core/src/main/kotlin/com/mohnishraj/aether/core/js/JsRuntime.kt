package com.mohnishraj.aether.core.js

import com.mohnishraj.aether.core.js.ast.JsExpression
import com.mohnishraj.aether.core.js.ast.JsProgram
import com.mohnishraj.aether.core.js.ast.JsStatement
import com.mohnishraj.aether.core.js.ast.VariableKind
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

internal class JsRealm(val limits: JsLimits) {
    val global = JsEnvironment(functionBoundary = true)
    val output = mutableListOf<String>()
    val tasks = JsTaskQueue(limits)
}

private data class ScheduledTask(
    val id: Long,
    val dueMillis: Long,
    val microtask: Boolean,
    val callback: JsValue.FunctionValue,
    val arguments: List<JsValue>,
    val intervalMillis: Long = 0L
) : Comparable<ScheduledTask> {
    override fun compareTo(other: ScheduledTask): Int {
        if (microtask != other.microtask) return if (microtask) -1 else 1
        val due = dueMillis.compareTo(other.dueMillis)
        return if (due != 0) due else id.compareTo(other.id)
    }
}

internal class JsTaskQueue(private val limits: JsLimits) {
    private val ids = AtomicLong(1)
    private val queue = PriorityQueue<ScheduledTask>()
    private val cancelled = mutableSetOf<Long>()
    var virtualTimeMillis: Long = 0L
        private set

    fun schedule(
        callback: JsValue.FunctionValue,
        delayMillis: Long,
        arguments: List<JsValue>,
        microtask: Boolean = false,
        intervalMillis: Long = 0L
    ): Long {
        if (queue.size >= limits.maxTimers) throw JsRuntimeException("RangeError", "Timer limit ${limits.maxTimers} reached")
        val id = ids.getAndIncrement()
        val safeDelay = delayMillis.coerceIn(0L, 2_147_483_647L)
        val safeInterval = intervalMillis.coerceIn(0L, 2_147_483_647L)
        queue += ScheduledTask(id, virtualTimeMillis + safeDelay, microtask, callback, arguments, safeInterval)
        return id
    }

    fun cancel(id: Long) { cancelled += id }
    fun advanceBy(millis: Long) { virtualTimeMillis = (virtualTimeMillis + millis.coerceAtLeast(0L)).coerceAtMost(Long.MAX_VALUE) }
    fun hasPendingTasks(): Boolean = queue.any { it.id !in cancelled }
    fun nextDelayMillis(): Long? = queue.filter { it.id !in cancelled }.minOfOrNull { task ->
        if (task.microtask) 0L else (task.dueMillis - virtualTimeMillis).coerceAtLeast(0L)
    }

    fun drain(context: JsExecutionContext): Int {
        var executed = 0
        while (queue.isNotEmpty() && executed < limits.maxTasksPerDrain) {
            val task = queue.peek()
            if (!task.microtask && task.dueMillis > virtualTimeMillis) break
            queue.poll()
            if (cancelled.remove(task.id)) continue
            context.call(task.callback, JsValue.Undefined, task.arguments)
            executed++
            if (task.intervalMillis > 0L && task.id !in cancelled) {
                queue += task.copy(dueMillis = virtualTimeMillis + task.intervalMillis)
            }
        }
        if (executed >= limits.maxTasksPerDrain && queue.isNotEmpty()) throw JsRuntimeException("RangeError", "Task drain limit ${limits.maxTasksPerDrain} reached")
        return executed
    }
}

class JsExecutionContext internal constructor(
    internal val realm: JsRealm,
    internal val interpreter: JsInterpreter
) {
    val limits: JsLimits get() = realm.limits
    fun call(function: JsValue.FunctionValue, thisValue: JsValue, arguments: List<JsValue>): JsValue = interpreter.call(function, thisValue, arguments)
    fun log(values: List<JsValue>) {
        if (realm.output.size >= limits.maxConsoleLines) throw JsRuntimeException("RangeError", "Console output limit ${limits.maxConsoleLines} reached")
        realm.output += values.joinToString(" ") { it.debugString() }
    }
    fun schedule(
        callback: JsValue.FunctionValue,
        delayMillis: Long,
        arguments: List<JsValue>,
        microtask: Boolean = false,
        intervalMillis: Long = 0L
    ): Long = realm.tasks.schedule(callback, delayMillis, arguments, microtask, intervalMillis)
    fun cancelTimer(id: Long) = realm.tasks.cancel(id)
    fun runtimeError(kind: String, message: String): Nothing = throw JsRuntimeException(kind, message)
}

class JsNativeFunction(
    override val name: String,
    override val length: Int,
    private val implementation: (JsExecutionContext, JsValue, List<JsValue>) -> JsValue
) : JsValue.FunctionValue() {
    override fun call(context: JsExecutionContext, thisValue: JsValue, arguments: List<JsValue>): JsValue = implementation(context, thisValue, arguments)
}

class JsPromiseValue private constructor(
    private var state: State,
    private var settledValue: JsValue
) : JsValue.ObjectValue() {
    private enum class State { PENDING, FULFILLED, REJECTED }
    private data class Handler(
        val onFulfilled: JsValue.FunctionValue?,
        val onRejected: JsValue.FunctionValue?,
        val child: JsPromiseValue
    )

    private val handlers = mutableListOf<Handler>()

    init {
        properties["then"] = JsNativeFunction("then", 2) { context, _, arguments ->
            val child = pending()
            val handler = Handler(arguments.getOrNull(0) as? JsValue.FunctionValue, arguments.getOrNull(1) as? JsValue.FunctionValue, child)
            handlers += handler
            if (state != State.PENDING) scheduleHandler(context, handler)
            child
        }
        properties["catch"] = JsNativeFunction("catch", 1) { context, _, arguments ->
            val then = properties.getValue("then") as JsValue.FunctionValue
            context.call(then, this, listOf(JsValue.Undefined, arguments.firstOrNull() ?: JsValue.Undefined))
        }
        properties["finally"] = JsNativeFunction("finally", 1) { context, _, arguments ->
            val callback = arguments.firstOrNull() as? JsValue.FunctionValue
            if (callback == null) return@JsNativeFunction this
            val pass = JsNativeFunction("finallyPass", 1) { callbackContext, _, values ->
                callbackContext.call(callback, JsValue.Undefined, emptyList())
                values.firstOrNull() ?: JsValue.Undefined
            }
            val fail = JsNativeFunction("finallyThrow", 1) { callbackContext, _, values ->
                callbackContext.call(callback, JsValue.Undefined, emptyList())
                throw JsThrownValue(values.firstOrNull() ?: JsValue.Undefined)
            }
            val then = properties.getValue("then") as JsValue.FunctionValue
            context.call(then, this, listOf(pass, fail))
        }
    }

    fun resolve(context: JsExecutionContext?, value: JsValue) {
        if (state != State.PENDING) return
        if (value === this) {
            reject(context, JsValue.StringValue("A promise cannot resolve to itself"))
            return
        }
        if (value is JsPromiseValue) {
            val adopt = JsNativeFunction("promiseAdopt", 1) { childContext, _, values -> resolve(childContext, values.firstOrNull() ?: JsValue.Undefined); JsValue.Undefined }
            val reject = JsNativeFunction("promiseReject", 1) { childContext, _, values -> reject(childContext, values.firstOrNull() ?: JsValue.Undefined); JsValue.Undefined }
            val then = value.properties.getValue("then") as JsValue.FunctionValue
            if (context != null) context.call(then, value, listOf(adopt, reject))
            return
        }
        state = State.FULFILLED
        settledValue = value
        context?.let(::flush)
    }

    fun reject(context: JsExecutionContext?, value: JsValue) {
        if (state != State.PENDING) return
        state = State.REJECTED
        settledValue = value
        context?.let(::flush)
    }

    private fun flush(context: JsExecutionContext) {
        handlers.toList().forEach { scheduleHandler(context, it) }
    }

    private fun scheduleHandler(context: JsExecutionContext, handler: Handler) {
        val task = JsNativeFunction("promiseReaction", 0) { taskContext, _, _ ->
            val callback = if (state == State.FULFILLED) handler.onFulfilled else handler.onRejected
            if (callback == null) {
                if (state == State.FULFILLED) handler.child.resolve(taskContext, settledValue)
                else handler.child.reject(taskContext, settledValue)
            } else {
                try {
                    handler.child.resolve(taskContext, taskContext.call(callback, JsValue.Undefined, listOf(settledValue)))
                } catch (thrown: JsThrownValue) {
                    handler.child.reject(taskContext, thrown.value)
                } catch (error: JsRuntimeException) {
                    handler.child.reject(taskContext, JsValue.ObjectValue(mapOf(
                        "name" to JsValue.StringValue(error.kind),
                        "message" to JsValue.StringValue(error.message)
                    )))
                }
            }
            JsValue.Undefined
        }
        context.schedule(task, 0L, emptyList(), microtask = true)
    }

    companion object {
        fun pending(): JsPromiseValue = JsPromiseValue(State.PENDING, JsValue.Undefined)
        fun fulfilled(value: JsValue): JsPromiseValue = JsPromiseValue(State.FULFILLED, value)
        fun rejected(value: JsValue): JsPromiseValue = JsPromiseValue(State.REJECTED, value)
    }
}

private class JsUserFunction(
    override val name: String,
    private val parameters: List<String>,
    private val body: JsStatement.Block,
    private val closure: JsEnvironment,
    private val interpreter: JsInterpreter
) : JsValue.FunctionValue() {
    override val length: Int get() = parameters.size
    override fun call(context: JsExecutionContext, thisValue: JsValue, arguments: List<JsValue>): JsValue {
        val environment = closure.child(functionBoundary = true)
        environment.declare("this", thisValue, mutable = false)
        parameters.forEachIndexed { index, parameter -> environment.declare(parameter, arguments.getOrElse(index) { JsValue.Undefined }, mutable = true) }
        val argumentsValue = JsValue.ArrayValue(arguments)
        environment.declare("arguments", argumentsValue, mutable = false)
        if (name.isNotBlank()) environment.declare(name, this, mutable = false, allowRedeclare = true)
        return try {
            when (val completion = interpreter.executeStatements(body.statements, environment)) {
                is Completion.Return -> completion.value
                is Completion.Normal -> JsValue.Undefined
                Completion.Break -> throw JsRuntimeException("SyntaxError", "Illegal break statement", body.span)
                Completion.Continue -> throw JsRuntimeException("SyntaxError", "Illegal continue statement", body.span)
            }
        } catch (error: JsRuntimeException) {
            throw JsRuntimeException(error.kind, error.message, error.span, error.frames + "${name.ifBlank { "anonymous" }}()")
        }
    }
}

internal class JsThrownValue(val value: JsValue) : RuntimeException(null, null, false, false)

internal sealed class Completion {
    data class Normal(val value: JsValue = JsValue.Undefined) : Completion()
    data class Return(val value: JsValue) : Completion()
    data object Break : Completion()
    data object Continue : Completion()
}

class JsInterpreter internal constructor(private val realm: JsRealm) {
    private val context = JsExecutionContext(realm, this)
    private var steps = 0L
    private var callDepth = 0

    fun execute(program: JsProgram): JsValue {
        val completion = executeStatements(program.statements, realm.global)
        return when (completion) {
            is Completion.Normal -> completion.value
            is Completion.Return -> throw JsRuntimeException("SyntaxError", "Illegal return statement", program.span)
            Completion.Break -> throw JsRuntimeException("SyntaxError", "Illegal break statement", program.span)
            Completion.Continue -> throw JsRuntimeException("SyntaxError", "Illegal continue statement", program.span)
        }
    }

    fun stepsExecuted(): Long = steps
    fun drainTasks(): Int = realm.tasks.drain(context)

    internal fun call(function: JsValue.FunctionValue, thisValue: JsValue, arguments: List<JsValue>): JsValue {
        step()
        if (arguments.size > realm.limits.maxCallArguments) throw JsRuntimeException("RangeError", "Call argument limit ${realm.limits.maxCallArguments} exceeded")
        if (callDepth >= realm.limits.maxCallDepth) throw JsRuntimeException("RangeError", "Maximum call depth ${realm.limits.maxCallDepth} exceeded")
        callDepth++
        return try { function.call(context, thisValue, arguments) } finally { callDepth-- }
    }

    internal fun executeStatements(statements: List<JsStatement>, environment: JsEnvironment): Completion {
        statements.filterIsInstance<JsStatement.FunctionDeclaration>().forEach { declaration ->
            if (environment.getOrNull(declaration.name) == null) {
                environment.declare(declaration.name, JsUserFunction(declaration.name, declaration.parameters, declaration.body, environment, this), mutable = true)
            }
        }
        var last: JsValue = JsValue.Undefined
        for (statement in statements) {
            val completion = executeStatement(statement, environment)
            when (completion) {
                is Completion.Normal -> last = completion.value
                else -> return completion
            }
        }
        return Completion.Normal(last)
    }

    private fun executeStatement(statement: JsStatement, environment: JsEnvironment): Completion {
        step(statement.span)
        return when (statement) {
            is JsStatement.Empty -> Completion.Normal()
            is JsStatement.Expression -> Completion.Normal(evaluate(statement.expression, environment))
            is JsStatement.Block -> executeStatements(statement.statements, environment.child())
            is JsStatement.VariableDeclaration -> {
                statement.declarations.forEach { declaration ->
                    val value = declaration.initializer?.let { evaluate(it, environment) } ?: JsValue.Undefined
                    when (statement.kind) {
                        VariableKind.CONST -> environment.declare(declaration.name, value, mutable = false)
                        VariableKind.LET -> environment.declare(declaration.name, value, mutable = true)
                        VariableKind.VAR -> environment.declareVar(declaration.name, value)
                    }
                }
                Completion.Normal()
            }
            is JsStatement.FunctionDeclaration -> Completion.Normal()
            is JsStatement.Return -> Completion.Return(statement.argument?.let { evaluate(it, environment) } ?: JsValue.Undefined)
            is JsStatement.If -> if (evaluate(statement.test, environment).isTruthy()) executeStatement(statement.consequent, environment)
                else statement.alternate?.let { executeStatement(it, environment) } ?: Completion.Normal()
            is JsStatement.While -> executeWhile(statement, environment)
            is JsStatement.For -> executeFor(statement, environment)
            is JsStatement.ForOf -> executeForOf(statement, environment)
            is JsStatement.Throw -> throw JsThrownValue(evaluate(statement.argument, environment))
            is JsStatement.Try -> executeTry(statement, environment)
            is JsStatement.Break -> Completion.Break
            is JsStatement.Continue -> Completion.Continue
        }
    }

    private fun executeWhile(statement: JsStatement.While, environment: JsEnvironment): Completion {
        var last: JsValue = JsValue.Undefined
        while (evaluate(statement.test, environment).isTruthy()) {
            step(statement.span)
            when (val completion = executeStatement(statement.body, environment)) {
                is Completion.Normal -> last = completion.value
                Completion.Continue -> Unit
                Completion.Break -> return Completion.Normal(last)
                is Completion.Return -> return completion
            }
        }
        return Completion.Normal(last)
    }

    private fun executeFor(statement: JsStatement.For, environment: JsEnvironment): Completion {
        val loopEnvironment = environment.child()
        statement.initializer?.let { executeStatement(it, loopEnvironment) }
        var last: JsValue = JsValue.Undefined
        while (statement.test?.let { evaluate(it, loopEnvironment).isTruthy() } != false) {
            step(statement.span)
            when (val completion = executeStatement(statement.body, loopEnvironment)) {
                is Completion.Normal -> last = completion.value
                Completion.Continue -> Unit
                Completion.Break -> return Completion.Normal(last)
                is Completion.Return -> return completion
            }
            statement.update?.let { evaluate(it, loopEnvironment) }
        }
        return Completion.Normal(last)
    }

    private fun executeForOf(statement: JsStatement.ForOf, environment: JsEnvironment): Completion {
        val values = when (val iterable = evaluate(statement.iterable, environment)) {
            is JsValue.ArrayValue -> iterable.elements.toList()
            is JsValue.StringValue -> iterable.value.map { JsValue.StringValue(it.toString()) }
            else -> throw JsRuntimeException("TypeError", "Value is not iterable", statement.iterable.span)
        }
        var last: JsValue = JsValue.Undefined
        for (value in values) {
            step(statement.span)
            val iteration = environment.child()
            iteration.declare(statement.variableName, value, mutable = statement.kind != VariableKind.CONST)
            when (val completion = executeStatement(statement.body, iteration)) {
                is Completion.Normal -> last = completion.value
                Completion.Continue -> Unit
                Completion.Break -> return Completion.Normal(last)
                is Completion.Return -> return completion
            }
        }
        return Completion.Normal(last)
    }

    private fun executeTry(statement: JsStatement.Try, environment: JsEnvironment): Completion {
        var completion: Completion? = null
        var pending: Throwable? = null
        try {
            completion = executeStatement(statement.block, environment)
        } catch (failure: Throwable) {
            val catchBlock = statement.catchBlock
            if (catchBlock == null || (failure !is JsThrownValue && failure !is JsRuntimeException)) {
                pending = failure
            } else {
                val catchEnvironment = environment.child()
                statement.catchParameter?.let { name ->
                    val value = when (failure) {
                        is JsThrownValue -> failure.value
                        is JsRuntimeException -> errorObject(failure)
                        else -> JsValue.Undefined
                    }
                    catchEnvironment.declare(name, value, mutable = true)
                }
                completion = executeStatement(catchBlock, catchEnvironment)
            }
        }
        statement.finalizer?.let { finalizer ->
            val finalCompletion = executeStatement(finalizer, environment)
            if (finalCompletion !is Completion.Normal) return finalCompletion
        }
        pending?.let { throw it }
        return completion ?: Completion.Normal()
    }

    private fun errorObject(error: JsRuntimeException): JsValue.ObjectValue = JsValue.ObjectValue(
        mapOf(
            "name" to JsValue.StringValue(error.kind),
            "message" to JsValue.StringValue(error.message),
            "stack" to JsValue.StringValue(error.pretty())
        )
    )

    private fun evaluate(expression: JsExpression, environment: JsEnvironment): JsValue {
        step(expression.span)
        return when (expression) {
            is JsExpression.Literal -> expression.value
            is JsExpression.Identifier -> environment.get(expression.name)
            is JsExpression.ArrayLiteral -> {
                if (expression.elements.size > realm.limits.maxArrayElements) throw JsRuntimeException("RangeError", "Array limit exceeded", expression.span)
                JsValue.ArrayValue(expression.elements.map { it?.let { item -> evaluate(item, environment) } ?: JsValue.Undefined })
            }
            is JsExpression.ObjectLiteral -> {
                if (expression.properties.size > realm.limits.maxObjectProperties) throw JsRuntimeException("RangeError", "Object property limit exceeded", expression.span)
                JsValue.ObjectValue(expression.properties.associate { it.key to evaluate(it.value, environment) })
            }
            is JsExpression.FunctionExpression -> JsUserFunction(expression.name.orEmpty(), expression.parameters, expression.body, environment, this)
            is JsExpression.Unary -> evaluateUnary(expression, environment)
            is JsExpression.Update -> evaluateUpdate(expression, environment)
            is JsExpression.Binary -> evaluateBinary(expression, environment)
            is JsExpression.Logical -> {
                val left = evaluate(expression.left, environment)
                if (expression.operator == "&&") { if (!left.isTruthy()) left else evaluate(expression.right, environment) }
                else { if (left.isTruthy()) left else evaluate(expression.right, environment) }
            }
            is JsExpression.Assignment -> evaluateAssignment(expression, environment)
            is JsExpression.Conditional -> if (evaluate(expression.test, environment).isTruthy()) evaluate(expression.consequent, environment) else evaluate(expression.alternate, environment)
            is JsExpression.Member -> {
                val target = evaluate(expression.target, environment)
                val property = propertyName(evaluate(expression.property, environment))
                getProperty(target, property)
            }
            is JsExpression.Call -> evaluateCall(expression, environment)
            is JsExpression.New -> evaluateNew(expression, environment)
        }
    }

    private fun evaluateUnary(expression: JsExpression.Unary, environment: JsEnvironment): JsValue {
        if (expression.operator == "typeof" && expression.argument is JsExpression.Identifier) {
            val value = environment.getOrNull(expression.argument.name) ?: return JsValue.StringValue("undefined")
            return JsValue.StringValue(value.typeName())
        }
        val argument = evaluate(expression.argument, environment)
        return when (expression.operator) {
            "!" -> JsValue.BooleanValue(!argument.isTruthy())
            "+" -> JsValue.NumberValue(argument.toNumber())
            "-" -> JsValue.NumberValue(-argument.toNumber())
            "~" -> JsValue.NumberValue(argument.toNumber().toInt().inv().toDouble())
            "typeof" -> JsValue.StringValue(argument.typeName())
            else -> JsValue.Undefined
        }
    }

    private fun evaluateUpdate(expression: JsExpression.Update, environment: JsEnvironment): JsValue {
        val current = readTarget(expression.argument, environment)
        val next = JsValue.NumberValue(current.toNumber() + if (expression.operator == "++") 1.0 else -1.0)
        writeTarget(expression.argument, next, environment)
        return if (expression.prefix) next else current
    }

    private fun evaluateBinary(expression: JsExpression.Binary, environment: JsEnvironment): JsValue {
        val left = evaluate(expression.left, environment)
        val right = evaluate(expression.right, environment)
        return when (expression.operator) {
            "+" -> if (left is JsValue.StringValue || right is JsValue.StringValue) stringValue(left.displayString() + right.displayString(), expression.span)
                else JsValue.NumberValue(left.toNumber() + right.toNumber())
            "-" -> JsValue.NumberValue(left.toNumber() - right.toNumber())
            "*" -> JsValue.NumberValue(left.toNumber() * right.toNumber())
            "/" -> JsValue.NumberValue(left.toNumber() / right.toNumber())
            "%" -> JsValue.NumberValue(left.toNumber() % right.toNumber())
            "==" -> JsValue.BooleanValue(JsValue.looseEquals(left, right))
            "!=" -> JsValue.BooleanValue(!JsValue.looseEquals(left, right))
            "===" -> JsValue.BooleanValue(JsValue.strictEquals(left, right))
            "!==" -> JsValue.BooleanValue(!JsValue.strictEquals(left, right))
            "<", "<=", ">", ">=" -> compare(left, right, expression.operator)
            else -> JsValue.Undefined
        }
    }

    private fun compare(left: JsValue, right: JsValue, operator: String): JsValue.BooleanValue {
        val comparison = if (left is JsValue.StringValue && right is JsValue.StringValue) left.value.compareTo(right.value).toDouble()
            else left.toNumber() - right.toNumber()
        val valid = !comparison.isNaN()
        return JsValue.BooleanValue(valid && when (operator) {
            "<" -> comparison < 0.0
            "<=" -> comparison <= 0.0
            ">" -> comparison > 0.0
            else -> comparison >= 0.0
        })
    }

    private fun evaluateAssignment(expression: JsExpression.Assignment, environment: JsEnvironment): JsValue {
        val value = if (expression.operator == "=") evaluate(expression.value, environment) else {
            val left = readTarget(expression.target, environment)
            val right = evaluate(expression.value, environment)
            when (expression.operator) {
                "+=" -> if (left is JsValue.StringValue || right is JsValue.StringValue) stringValue(left.displayString() + right.displayString(), expression.span)
                    else JsValue.NumberValue(left.toNumber() + right.toNumber())
                "-=" -> JsValue.NumberValue(left.toNumber() - right.toNumber())
                "*=" -> JsValue.NumberValue(left.toNumber() * right.toNumber())
                "/=" -> JsValue.NumberValue(left.toNumber() / right.toNumber())
                "%=" -> JsValue.NumberValue(left.toNumber() % right.toNumber())
                else -> JsValue.Undefined
            }
        }
        writeTarget(expression.target, value, environment)
        return value
    }

    private fun evaluateCall(expression: JsExpression.Call, environment: JsEnvironment): JsValue {
        val thisValue: JsValue
        val callee: JsValue
        if (expression.callee is JsExpression.Member) {
            thisValue = evaluate(expression.callee.target, environment)
            val property = propertyName(evaluate(expression.callee.property, environment))
            callee = getProperty(thisValue, property)
        } else {
            thisValue = JsValue.Undefined
            callee = evaluate(expression.callee, environment)
        }
        if (callee !is JsValue.FunctionValue) throw JsRuntimeException("TypeError", "${callee.displayString()} is not a function", expression.span)
        val arguments = expression.arguments.map { evaluate(it, environment) }
        return call(callee, thisValue, arguments)
    }

    private fun evaluateNew(expression: JsExpression.New, environment: JsEnvironment): JsValue {
        val constructor = evaluate(expression.constructor, environment)
        if (constructor !is JsValue.FunctionValue) throw JsRuntimeException("TypeError", "${constructor.displayString()} is not a constructor", expression.span)
        val instance = JsValue.ObjectValue()
        val arguments = expression.arguments.map { evaluate(it, environment) }
        val returned = call(constructor, instance, arguments)
        return if (returned is JsValue.ObjectValue || returned is JsValue.FunctionValue || returned is JsValue.ArrayValue) returned else instance
    }

    private fun readTarget(target: JsExpression, environment: JsEnvironment): JsValue = when (target) {
        is JsExpression.Identifier -> environment.get(target.name)
        is JsExpression.Member -> getProperty(evaluate(target.target, environment), propertyName(evaluate(target.property, environment)))
        else -> throw JsRuntimeException("ReferenceError", "Invalid assignment target", target.span)
    }

    private fun writeTarget(target: JsExpression, value: JsValue, environment: JsEnvironment) {
        when (target) {
            is JsExpression.Identifier -> environment.assign(target.name, value)
            is JsExpression.Member -> setProperty(evaluate(target.target, environment), propertyName(evaluate(target.property, environment)), value, target.span)
            else -> throw JsRuntimeException("ReferenceError", "Invalid assignment target", target.span)
        }
    }

    private fun propertyName(value: JsValue): String = value.displayString()

    private fun getProperty(target: JsValue, property: String): JsValue = when (target) {
        JsValue.Undefined, JsValue.Null -> throw JsRuntimeException("TypeError", "Cannot read properties of ${target.displayString()}")
        is JsValue.ObjectValue -> target.getProperty(context, property) ?: JsValue.Undefined
        is JsValue.ArrayValue -> arrayProperty(target, property)
        is JsValue.StringValue -> stringProperty(target, property)
        is JsValue.NumberValue -> numberProperty(target, property)
        is JsValue.BooleanValue -> JsValue.Undefined
        is JsValue.FunctionValue -> when (property) {
            "name" -> JsValue.StringValue(target.name)
            "length" -> JsValue.NumberValue(target.length.toDouble())
            else -> JsBuiltins.property(target, property) ?: JsValue.Undefined
        }
    }

    private fun setProperty(target: JsValue, property: String, value: JsValue, span: JsSourceSpan) {
        when (target) {
            is JsValue.ObjectValue -> {
                if (!target.properties.containsKey(property) && target.properties.size >= realm.limits.maxObjectProperties) throw JsRuntimeException("RangeError", "Object property limit exceeded", span)
                if (!target.setProperty(context, property, value)) throw JsRuntimeException("TypeError", "Cannot assign to property '$property'", span)
            }
            is JsValue.ArrayValue -> {
                val index = arrayIndex(property)
                if (index != null) {
                    if (index >= realm.limits.maxArrayElements) throw JsRuntimeException("RangeError", "Array element limit exceeded", span)
                    while (target.elements.size <= index) target.elements += JsValue.Undefined
                    target.elements[index] = value
                } else if (property == "length") {
                    val newLength = value.toNumber().toInt()
                    if (newLength < 0 || newLength > realm.limits.maxArrayElements) throw JsRuntimeException("RangeError", "Invalid array length", span)
                    while (target.elements.size > newLength) target.elements.removeAt(target.elements.lastIndex)
                    while (target.elements.size < newLength) target.elements += JsValue.Undefined
                } else target.properties[property] = value
            }
            is JsValue.FunctionValue -> JsBuiltins.setProperty(target, property, value)
            JsValue.Undefined, JsValue.Null -> throw JsRuntimeException("TypeError", "Cannot set properties of ${target.displayString()}", span)
            else -> throw JsRuntimeException("TypeError", "Cannot create property '$property' on primitive value", span)
        }
    }

    private fun arrayProperty(array: JsValue.ArrayValue, property: String): JsValue {
        val index = arrayIndex(property)
        if (index != null) return array.elements.getOrElse(index) { JsValue.Undefined }
        array.properties[property]?.let { return it }
        return when (property) {
            "length" -> JsValue.NumberValue(array.elements.size.toDouble())
            "push" -> JsNativeFunction("push", 1) { _, _, arguments ->
                if (array.elements.size + arguments.size > realm.limits.maxArrayElements) throw JsRuntimeException("RangeError", "Array element limit exceeded")
                array.elements.addAll(arguments)
                JsValue.NumberValue(array.elements.size.toDouble())
            }
            "pop" -> JsNativeFunction("pop", 0) { _, _, _ -> if (array.elements.isEmpty()) JsValue.Undefined else array.elements.removeAt(array.elements.lastIndex) }
            "shift" -> JsNativeFunction("shift", 0) { _, _, _ -> if (array.elements.isEmpty()) JsValue.Undefined else array.elements.removeAt(0) }
            "unshift" -> JsNativeFunction("unshift", 1) { _, _, arguments ->
                if (array.elements.size + arguments.size > realm.limits.maxArrayElements) throw JsRuntimeException("RangeError", "Array element limit exceeded")
                array.elements.addAll(0, arguments)
                JsValue.NumberValue(array.elements.size.toDouble())
            }
            "join" -> JsNativeFunction("join", 1) { _, _, arguments ->
                val separator = arguments.firstOrNull()?.displayString() ?: ","
                stringValue(array.elements.joinToString(separator) { if (it === JsValue.Null || it === JsValue.Undefined) "" else it.displayString() })
            }
            "includes" -> JsNativeFunction("includes", 1) { _, _, arguments -> JsValue.BooleanValue(array.elements.any { JsValue.strictEquals(it, arguments.firstOrNull() ?: JsValue.Undefined) }) }
            "indexOf" -> JsNativeFunction("indexOf", 1) { _, _, arguments ->
                JsValue.NumberValue(array.elements.indexOfFirst { JsValue.strictEquals(it, arguments.firstOrNull() ?: JsValue.Undefined) }.toDouble())
            }
            "slice" -> JsNativeFunction("slice", 2) { _, _, arguments ->
                val size = array.elements.size
                val startIndex = normalizeIndex(arguments.getOrNull(0)?.toNumber()?.toInt() ?: 0, size)
                val endIndex = normalizeIndex(arguments.getOrNull(1)?.toNumber()?.toInt() ?: size, size).coerceAtLeast(startIndex)
                JsValue.ArrayValue(array.elements.subList(startIndex, endIndex))
            }
            "concat" -> JsNativeFunction("concat", 1) { _, _, arguments ->
                val output = array.elements.toMutableList()
                arguments.forEach { item -> if (item is JsValue.ArrayValue) output.addAll(item.elements) else output += item }
                if (output.size > realm.limits.maxArrayElements) throw JsRuntimeException("RangeError", "Array element limit exceeded")
                JsValue.ArrayValue(output)
            }
            "forEach", "map", "filter", "find", "some", "every" -> JsNativeFunction(property, 1) { execution, _, arguments ->
                val callback = arguments.firstOrNull() as? JsValue.FunctionValue ?: throw JsRuntimeException("TypeError", "$property callback must be a function")
                val output = mutableListOf<JsValue>()
                var found: JsValue = JsValue.Undefined
                var booleanResult = property == "every"
                for ((itemIndex, item) in array.elements.toList().withIndex()) {
                    val result = execution.call(callback, JsValue.Undefined, listOf(item, JsValue.NumberValue(itemIndex.toDouble()), array))
                    when (property) {
                        "map" -> output += result
                        "filter" -> if (result.isTruthy()) output += item
                        "find" -> if (result.isTruthy()) { found = item; break }
                        "some" -> if (result.isTruthy()) { booleanResult = true; break }
                        "every" -> if (!result.isTruthy()) { booleanResult = false; break }
                    }
                }
                when (property) {
                    "map", "filter" -> JsValue.ArrayValue(output)
                    "find" -> found
                    "some", "every" -> JsValue.BooleanValue(booleanResult)
                    else -> JsValue.Undefined
                }
            }
            "reduce" -> JsNativeFunction("reduce", 2) { execution, _, arguments ->
                val callback = arguments.firstOrNull() as? JsValue.FunctionValue ?: throw JsRuntimeException("TypeError", "reduce callback must be a function")
                if (array.elements.isEmpty() && arguments.size < 2) throw JsRuntimeException("TypeError", "Reduce of empty array with no initial value")
                var indexValue = if (arguments.size >= 2) 0 else 1
                var accumulator = if (arguments.size >= 2) arguments[1] else array.elements.first()
                while (indexValue < array.elements.size) {
                    accumulator = execution.call(callback, JsValue.Undefined, listOf(accumulator, array.elements[indexValue], JsValue.NumberValue(indexValue.toDouble()), array))
                    indexValue++
                }
                accumulator
            }
            else -> JsValue.Undefined
        }
    }

    private fun stringProperty(string: JsValue.StringValue, property: String): JsValue = when (property) {
        "length" -> JsValue.NumberValue(string.value.length.toDouble())
        "charAt" -> JsNativeFunction("charAt", 1) { _, _, arguments -> stringValue(string.value.getOrNull(arguments.firstOrNull()?.toNumber()?.toInt() ?: 0)?.toString().orEmpty()) }
        "includes" -> JsNativeFunction("includes", 1) { _, _, arguments -> JsValue.BooleanValue(string.value.contains(arguments.firstOrNull()?.displayString().orEmpty())) }
        "startsWith" -> JsNativeFunction("startsWith", 1) { _, _, arguments -> JsValue.BooleanValue(string.value.startsWith(arguments.firstOrNull()?.displayString().orEmpty())) }
        "endsWith" -> JsNativeFunction("endsWith", 1) { _, _, arguments -> JsValue.BooleanValue(string.value.endsWith(arguments.firstOrNull()?.displayString().orEmpty())) }
        "indexOf" -> JsNativeFunction("indexOf", 1) { _, _, arguments -> JsValue.NumberValue(string.value.indexOf(arguments.firstOrNull()?.displayString().orEmpty()).toDouble()) }
        "trim" -> JsNativeFunction("trim", 0) { _, _, _ -> stringValue(string.value.trim()) }
        "toUpperCase" -> JsNativeFunction("toUpperCase", 0) { _, _, _ -> stringValue(string.value.uppercase()) }
        "toLowerCase" -> JsNativeFunction("toLowerCase", 0) { _, _, _ -> stringValue(string.value.lowercase()) }
        "substring" -> JsNativeFunction("substring", 2) { _, _, arguments ->
            val first = (arguments.getOrNull(0)?.toNumber()?.toInt() ?: 0).coerceIn(0, string.value.length)
            val second = (arguments.getOrNull(1)?.toNumber()?.toInt() ?: string.value.length).coerceIn(0, string.value.length)
            stringValue(string.value.substring(min(first, second), max(first, second)))
        }
        "slice" -> JsNativeFunction("slice", 2) { _, _, arguments ->
            val first = normalizeIndex(arguments.getOrNull(0)?.toNumber()?.toInt() ?: 0, string.value.length)
            val second = normalizeIndex(arguments.getOrNull(1)?.toNumber()?.toInt() ?: string.value.length, string.value.length).coerceAtLeast(first)
            stringValue(string.value.substring(first, second))
        }
        "split" -> JsNativeFunction("split", 2) { _, _, arguments ->
            val separator = arguments.firstOrNull()?.takeUnless { it === JsValue.Undefined }?.displayString()
            val limit = (arguments.getOrNull(1)?.toNumber()?.toInt() ?: realm.limits.maxArrayElements).coerceIn(0, realm.limits.maxArrayElements)
            val parts = if (separator == null) listOf(string.value) else if (separator.isEmpty()) string.value.map(Char::toString) else string.value.split(separator)
            JsValue.ArrayValue(parts.take(limit).map(JsValue::StringValue))
        }
        "replace" -> JsNativeFunction("replace", 2) { _, _, arguments ->
            val target = arguments.firstOrNull()?.displayString().orEmpty()
            val replacement = arguments.getOrNull(1)?.displayString().orEmpty()
            stringValue(string.value.replaceFirst(target, replacement))
        }
        else -> arrayIndex(property)?.let { index -> string.value.getOrNull(index)?.let { stringValue(it.toString()) } } ?: JsValue.Undefined
    }

    private fun numberProperty(number: JsValue.NumberValue, property: String): JsValue = when (property) {
        "toString" -> JsNativeFunction("toString", 0) { _, _, _ -> stringValue(number.displayString()) }
        else -> JsValue.Undefined
    }

    private fun stringValue(value: String, span: JsSourceSpan = JsSourceSpan.UNKNOWN): JsValue.StringValue {
        if (value.length > realm.limits.maxStringChars) throw JsRuntimeException("RangeError", "String length limit ${realm.limits.maxStringChars} exceeded", span)
        return JsValue.StringValue(value)
    }

    private fun step(span: JsSourceSpan = JsSourceSpan.UNKNOWN) {
        steps++
        if (steps > realm.limits.maxSteps) throw JsRuntimeException("RangeError", "Execution step limit ${realm.limits.maxSteps} exceeded", span)
    }

    private fun arrayIndex(property: String): Int? {
        if (property.isEmpty() || property.any { !it.isDigit() }) return null
        return property.toIntOrNull()?.takeIf { it >= 0 }
    }

    private fun normalizeIndex(index: Int, size: Int): Int = if (index < 0) (size + index).coerceAtLeast(0) else index.coerceAtMost(size)
}

internal object JsBuiltins {
    private val functionProperties = java.util.IdentityHashMap<JsValue.FunctionValue, MutableMap<String, JsValue>>()

    fun property(function: JsValue.FunctionValue, name: String): JsValue? = functionProperties[function]?.get(name)
    fun setProperty(function: JsValue.FunctionValue, name: String, value: JsValue) { objectConstructorProperties(function)[name] = value }
    private fun objectConstructorProperties(function: JsValue.FunctionValue): MutableMap<String, JsValue> =
        functionProperties.getOrPut(function) { linkedMapOf() }

    fun install(realm: JsRealm) {
        val global = realm.global
        fun native(name: String, length: Int, implementation: (JsExecutionContext, JsValue, List<JsValue>) -> JsValue): JsNativeFunction =
            JsNativeFunction(name, length, implementation)

        val console = JsValue.ObjectValue()
        listOf("log", "info", "warn", "error").forEach { level ->
            console.properties[level] = native(level, 1) { context, _, arguments -> context.log(arguments); JsValue.Undefined }
        }
        global.declare("console", console, mutable = false)

        val math = JsValue.ObjectValue(mapOf(
            "PI" to JsValue.NumberValue(Math.PI),
            "E" to JsValue.NumberValue(Math.E),
            "abs" to native("abs", 1) { _, _, args -> JsValue.NumberValue(kotlin.math.abs(args.firstOrNull()?.toNumber() ?: Double.NaN)) },
            "floor" to native("floor", 1) { _, _, args -> JsValue.NumberValue(floor(args.firstOrNull()?.toNumber() ?: Double.NaN)) },
            "ceil" to native("ceil", 1) { _, _, args -> JsValue.NumberValue(ceil(args.firstOrNull()?.toNumber() ?: Double.NaN)) },
            "round" to native("round", 1) { _, _, args -> JsValue.NumberValue(round(args.firstOrNull()?.toNumber() ?: Double.NaN)) },
            "sqrt" to native("sqrt", 1) { _, _, args -> JsValue.NumberValue(sqrt(args.firstOrNull()?.toNumber() ?: Double.NaN)) },
            "pow" to native("pow", 2) { _, _, args -> JsValue.NumberValue((args.getOrNull(0)?.toNumber() ?: Double.NaN).pow(args.getOrNull(1)?.toNumber() ?: Double.NaN)) },
            "min" to native("min", 2) { _, _, args -> JsValue.NumberValue(args.minOfOrNull { it.toNumber() } ?: Double.POSITIVE_INFINITY) },
            "max" to native("max", 2) { _, _, args -> JsValue.NumberValue(args.maxOfOrNull { it.toNumber() } ?: Double.NEGATIVE_INFINITY) }
        ))
        global.declare("Math", math, mutable = false)

        global.declare("Number", native("Number", 1) { _, _, args -> JsValue.NumberValue(args.firstOrNull()?.toNumber() ?: 0.0) }, mutable = false)
        global.declare("String", native("String", 1) { _, _, args -> JsValue.StringValue(args.firstOrNull()?.displayString() ?: "") }, mutable = false)
        global.declare("Boolean", native("Boolean", 1) { _, _, args -> JsValue.BooleanValue(args.firstOrNull()?.isTruthy() == true) }, mutable = false)
        val objectConstructor = native("Object", 1) { _, _, args ->
            when (val value = args.firstOrNull()) {
                null, JsValue.Null, JsValue.Undefined -> JsValue.ObjectValue()
                is JsValue.ObjectValue, is JsValue.ArrayValue, is JsValue.FunctionValue -> value
                else -> JsValue.ObjectValue(mapOf("value" to value))
            }
        }
        val objectNamespace = JsValue.ObjectValue(mapOf(
            "keys" to native("keys", 1) { _, _, args ->
                val value = args.firstOrNull() as? JsValue.ObjectValue ?: return@native JsValue.ArrayValue()
                JsValue.ArrayValue(value.ownPropertyNames().map(JsValue::StringValue))
            },
            "values" to native("values", 1) { context, _, args ->
                val value = args.firstOrNull() as? JsValue.ObjectValue ?: return@native JsValue.ArrayValue()
                JsValue.ArrayValue(value.ownPropertyNames().map { value.getProperty(context, it) ?: JsValue.Undefined })
            },
            "entries" to native("entries", 1) { context, _, args ->
                val value = args.firstOrNull() as? JsValue.ObjectValue ?: return@native JsValue.ArrayValue()
                JsValue.ArrayValue(value.ownPropertyNames().map { key -> JsValue.ArrayValue(listOf(JsValue.StringValue(key), value.getProperty(context, key) ?: JsValue.Undefined)) })
            },
            "assign" to native("assign", 2) { context, _, args ->
                val target = args.firstOrNull() as? JsValue.ObjectValue ?: throw JsRuntimeException("TypeError", "Object.assign target must be an object")
                args.drop(1).filterIsInstance<JsValue.ObjectValue>().forEach { source ->
                    source.ownPropertyNames().forEach { key -> target.setProperty(context, key, source.getProperty(context, key) ?: JsValue.Undefined) }
                }
                target
            }
        ))
        objectNamespace.properties.forEach { (name, value) -> objectConstructorProperties(objectConstructor)[name] = value }
        global.declare("Object", objectConstructor, mutable = false)

        val arrayConstructor = native("Array", 1) { _, _, args ->
            if (args.size == 1 && args[0] is JsValue.NumberValue) {
                val size = args[0].toNumber().toInt()
                if (size !in 0..realm.limits.maxArrayElements) throw JsRuntimeException("RangeError", "Invalid array length")
                JsValue.ArrayValue(List(size) { JsValue.Undefined })
            } else JsValue.ArrayValue(args)
        }
        objectConstructorProperties(arrayConstructor)["isArray"] = native("isArray", 1) { _, _, args -> JsValue.BooleanValue(args.firstOrNull() is JsValue.ArrayValue) }
        global.declare("Array", arrayConstructor, mutable = false)

        val promiseConstructor = native("Promise", 1) { context, _, args ->
            val executor = args.firstOrNull() as? JsValue.FunctionValue ?: throw JsRuntimeException("TypeError", "Promise executor must be a function")
            val promise = JsPromiseValue.pending()
            val resolve = native("resolve", 1) { resolveContext, _, values -> promise.resolve(resolveContext, values.firstOrNull() ?: JsValue.Undefined); JsValue.Undefined }
            val reject = native("reject", 1) { rejectContext, _, values -> promise.reject(rejectContext, values.firstOrNull() ?: JsValue.Undefined); JsValue.Undefined }
            try { context.call(executor, JsValue.Undefined, listOf(resolve, reject)) }
            catch (thrown: JsThrownValue) { promise.reject(context, thrown.value) }
            catch (error: JsRuntimeException) { promise.reject(context, JsValue.ObjectValue(mapOf("name" to JsValue.StringValue(error.kind), "message" to JsValue.StringValue(error.message)))) }
            promise
        }
        objectConstructorProperties(promiseConstructor)["resolve"] = native("resolve", 1) { _, _, args ->
            val value = args.firstOrNull() ?: JsValue.Undefined
            if (value is JsPromiseValue) value else JsPromiseValue.fulfilled(value)
        }
        objectConstructorProperties(promiseConstructor)["reject"] = native("reject", 1) { _, _, args -> JsPromiseValue.rejected(args.firstOrNull() ?: JsValue.Undefined) }
        global.declare("Promise", promiseConstructor, mutable = false)

        val dateConstructor = native("Date", 0) { _, _, _ -> JsValue.ObjectValue(mapOf("value" to JsValue.NumberValue(System.currentTimeMillis().toDouble()))) }
        objectConstructorProperties(dateConstructor)["now"] = native("now", 0) { _, _, _ -> JsValue.NumberValue(System.currentTimeMillis().toDouble()) }
        global.declare("Date", dateConstructor, mutable = false)
        global.declare("Error", native("Error", 1) { _, _, args -> JsValue.ObjectValue(mapOf(
            "name" to JsValue.StringValue("Error"),
            "message" to JsValue.StringValue(args.firstOrNull()?.displayString().orEmpty())
        )) }, mutable = false)
        global.declare("parseInt", native("parseInt", 2) { _, _, args ->
            var raw = args.firstOrNull()?.displayString()?.trim().orEmpty()
            var sign = 1
            if (raw.startsWith("+")) raw = raw.drop(1)
            else if (raw.startsWith("-")) { sign = -1; raw = raw.drop(1) }
            var radix = (args.getOrNull(1)?.toNumber()?.toInt() ?: 10).takeIf { it in 2..36 } ?: 10
            if (args.getOrNull(1) == null && raw.startsWith("0x", ignoreCase = true)) { radix = 16; raw = raw.drop(2) }
            val digits = raw.takeWhile { Character.digit(it, radix) >= 0 }
            val parsed = digits.toLongOrNull(radix)?.toDouble()?.times(sign)
            JsValue.NumberValue(parsed ?: Double.NaN)
        }, mutable = false)
        global.declare("parseFloat", native("parseFloat", 1) { _, _, args ->
            val match = Regex("^[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?").find(args.firstOrNull()?.displayString()?.trim().orEmpty())?.value
            JsValue.NumberValue(match?.toDoubleOrNull() ?: Double.NaN)
        }, mutable = false)
        global.declare("isNaN", native("isNaN", 1) { _, _, args -> JsValue.BooleanValue((args.firstOrNull() ?: JsValue.Undefined).toNumber().isNaN()) }, mutable = false)

        global.declare("setTimeout", native("setTimeout", 2) { context, _, args ->
            val callback = args.firstOrNull() as? JsValue.FunctionValue ?: context.runtimeError("TypeError", "setTimeout callback must be a function")
            val id = context.schedule(callback, (args.getOrNull(1)?.toNumber() ?: 0.0).toLong(), args.drop(2))
            JsValue.NumberValue(id.toDouble())
        }, mutable = false)
        global.declare("clearTimeout", native("clearTimeout", 1) { context, _, args -> context.cancelTimer((args.firstOrNull()?.toNumber() ?: 0.0).toLong()); JsValue.Undefined }, mutable = false)
        global.declare("setInterval", native("setInterval", 2) { context, _, args ->
            val callback = args.firstOrNull() as? JsValue.FunctionValue ?: context.runtimeError("TypeError", "setInterval callback must be a function")
            val delay = (args.getOrNull(1)?.toNumber() ?: 0.0).toLong().coerceAtLeast(1L)
            val id = context.schedule(callback, delay, args.drop(2), intervalMillis = delay)
            JsValue.NumberValue(id.toDouble())
        }, mutable = false)
        global.declare("clearInterval", native("clearInterval", 1) { context, _, args -> context.cancelTimer((args.firstOrNull()?.toNumber() ?: 0.0).toLong()); JsValue.Undefined }, mutable = false)
        global.declare("queueMicrotask", native("queueMicrotask", 1) { context, _, args ->
            val callback = args.firstOrNull() as? JsValue.FunctionValue ?: context.runtimeError("TypeError", "queueMicrotask callback must be a function")
            context.schedule(callback, 0L, emptyList(), microtask = true)
            JsValue.Undefined
        }, mutable = false)

        val json = JsValue.ObjectValue(mapOf(
            "stringify" to native("stringify", 1) { _, _, args -> JsValue.StringValue(JsJson.stringify(args.firstOrNull() ?: JsValue.Undefined, realm.limits)) },
            "parse" to native("parse", 1) { _, _, args -> JsJson.parse(args.firstOrNull()?.displayString().orEmpty(), realm.limits) }
        ))
        global.declare("JSON", json, mutable = false)
        global.declare("encodeURIComponent", native("encodeURIComponent", 1) { _, _, args ->
            JsValue.StringValue(URLEncoder.encode(args.firstOrNull()?.displayString().orEmpty(), StandardCharsets.UTF_8).replace("+", "%20"))
        }, mutable = false)
        global.declare("decodeURIComponent", native("decodeURIComponent", 1) { _, _, args ->
            runCatching { JsValue.StringValue(URLDecoder.decode(args.firstOrNull()?.displayString().orEmpty(), StandardCharsets.UTF_8)) }
                .getOrElse { throw JsRuntimeException("URIError", it.message ?: "Malformed URI") }
        }, mutable = false)
        global.declare("undefined", JsValue.Undefined, mutable = false)
        global.declare("NaN", JsValue.NumberValue(Double.NaN), mutable = false)
        global.declare("Infinity", JsValue.NumberValue(Double.POSITIVE_INFINITY), mutable = false)
    }
}

internal object JsJson {
    fun parse(source: String, limits: JsLimits): JsValue = JsonReader(source, limits).parse()

    fun stringify(value: JsValue, limits: JsLimits): String {
        val seen = mutableSetOf<JsValue>()
        val result = encode(value, seen, limits, 0) ?: return "undefined"
        if (result.length > limits.maxStringChars) throw JsRuntimeException("RangeError", "JSON output exceeds string limit")
        return result
    }

    private fun encode(value: JsValue, seen: MutableSet<JsValue>, limits: JsLimits, depth: Int): String? {
        if (depth > limits.maxCallDepth) throw JsRuntimeException("RangeError", "JSON nesting limit exceeded")
        return when (value) {
            JsValue.Undefined, is JsValue.FunctionValue -> null
            JsValue.Null -> "null"
            is JsValue.BooleanValue -> value.value.toString()
            is JsValue.NumberValue -> if (value.value.isFinite()) value.displayString() else "null"
            is JsValue.StringValue -> quote(value.value)
            is JsValue.ArrayValue -> {
                if (!seen.add(value)) throw JsRuntimeException("TypeError", "Converting circular structure to JSON")
                val rendered = value.elements.joinToString(",") { encode(it, seen, limits, depth + 1) ?: "null" }
                seen.remove(value)
                "[$rendered]"
            }
            is JsValue.ObjectValue -> {
                if (!seen.add(value)) throw JsRuntimeException("TypeError", "Converting circular structure to JSON")
                val rendered = value.properties.entries.mapNotNull { (key, item) -> encode(item, seen, limits, depth + 1)?.let { "${quote(key)}:$it" } }.joinToString(",")
                seen.remove(value)
                "{$rendered}"
            }
        }
    }

    private class JsonReader(private val source: String, private val limits: JsLimits) {
        private var index = 0
        private var nodes = 0

        fun parse(): JsValue {
            if (source.length > limits.maxSourceChars) throw JsRuntimeException("RangeError", "JSON input exceeds source limit")
            skipWhitespace()
            val value = readValue(0)
            skipWhitespace()
            if (index != source.length) syntax("Unexpected trailing JSON data")
            return value
        }

        private fun readValue(depth: Int): JsValue {
            if (depth > limits.maxCallDepth) throw JsRuntimeException("RangeError", "JSON nesting limit exceeded")
            if (++nodes > limits.maxAstNodes) throw JsRuntimeException("RangeError", "JSON node limit exceeded")
            skipWhitespace()
            return when (peek()) {
                'n' -> { expect("null"); JsValue.Null }
                't' -> { expect("true"); JsValue.BooleanValue(true) }
                'f' -> { expect("false"); JsValue.BooleanValue(false) }
                '"' -> JsValue.StringValue(readString())
                '[' -> readArray(depth + 1)
                '{' -> readObject(depth + 1)
                '-', in '0'..'9' -> readNumber()
                else -> syntax("Expected JSON value")
            }
        }

        private fun readArray(depth: Int): JsValue.ArrayValue {
            index++
            skipWhitespace()
            val values = mutableListOf<JsValue>()
            if (peek() == ']') { index++; return JsValue.ArrayValue() }
            while (true) {
                if (values.size >= limits.maxArrayElements) throw JsRuntimeException("RangeError", "JSON array limit exceeded")
                values += readValue(depth)
                skipWhitespace()
                when (peek()) {
                    ',' -> { index++; skipWhitespace() }
                    ']' -> { index++; return JsValue.ArrayValue(values) }
                    else -> syntax("Expected ',' or ']' in JSON array")
                }
            }
        }

        private fun readObject(depth: Int): JsValue.ObjectValue {
            index++
            skipWhitespace()
            val values = linkedMapOf<String, JsValue>()
            if (peek() == '}') { index++; return JsValue.ObjectValue() }
            while (true) {
                if (values.size >= limits.maxObjectProperties) throw JsRuntimeException("RangeError", "JSON object limit exceeded")
                if (peek() != '"') syntax("Expected string property name")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') syntax("Expected ':' after property name")
                index++
                values[key] = readValue(depth)
                skipWhitespace()
                when (peek()) {
                    ',' -> { index++; skipWhitespace() }
                    '}' -> { index++; return JsValue.ObjectValue(values) }
                    else -> syntax("Expected ',' or '}' in JSON object")
                }
            }
        }

        private fun readNumber(): JsValue.NumberValue {
            val start = index
            if (peek() == '-') index++
            if (peek() == '0') index++ else {
                if (peek() !in '1'..'9') syntax("Invalid JSON number")
                while (peek().isDigit()) index++
            }
            if (peek() == '.') {
                index++
                if (!peek().isDigit()) syntax("Invalid JSON fraction")
                while (peek().isDigit()) index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                if (!peek().isDigit()) syntax("Invalid JSON exponent")
                while (peek().isDigit()) index++
            }
            return JsValue.NumberValue(source.substring(start, index).toDoubleOrNull() ?: syntax("Invalid JSON number"))
        }

        private fun readString(): String {
            if (peek() != '"') syntax("Expected JSON string")
            index++
            val output = StringBuilder()
            while (index < source.length) {
                val character = source[index++]
                when (character) {
                    '"' -> {
                        if (output.length > limits.maxStringChars) throw JsRuntimeException("RangeError", "JSON string limit exceeded")
                        return output.toString()
                    }
                    '\\' -> {
                        if (index >= source.length) syntax("Unterminated JSON escape")
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> output.append(escaped)
                            'b' -> output.append('\b')
                            'f' -> output.append('\u000C')
                            'n' -> output.append('\n')
                            'r' -> output.append('\r')
                            't' -> output.append('\t')
                            'u' -> {
                                if (index + 4 > source.length) syntax("Incomplete Unicode escape")
                                val raw = source.substring(index, index + 4)
                                val code = raw.toIntOrNull(16) ?: syntax("Invalid Unicode escape")
                                output.append(code.toChar())
                                index += 4
                            }
                            else -> syntax("Invalid JSON escape \\$escaped")
                        }
                    }
                    else -> {
                        if (character.code < 0x20) syntax("Control character in JSON string")
                        output.append(character)
                    }
                }
            }
            syntax("Unterminated JSON string")
        }

        private fun expect(value: String) {
            if (!source.regionMatches(index, value, 0, value.length)) syntax("Expected '$value'")
            index += value.length
        }

        private fun skipWhitespace() {
            while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') index++
        }

        private fun peek(): Char = source.getOrElse(index) { '\u0000' }
        private fun syntax(message: String): Nothing = throw JsRuntimeException("SyntaxError", "$message at JSON offset $index")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
