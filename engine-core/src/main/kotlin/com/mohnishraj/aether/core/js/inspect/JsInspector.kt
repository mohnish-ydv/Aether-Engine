package com.mohnishraj.aether.core.js.inspect

import com.mohnishraj.aether.core.js.JsEvaluationResult
import com.mohnishraj.aether.core.js.JsParseResult
import com.mohnishraj.aether.core.js.JsToken
import com.mohnishraj.aether.core.js.JsValue
import com.mohnishraj.aether.core.js.ast.JsExpression
import com.mohnishraj.aether.core.js.ast.JsNode
import com.mohnishraj.aether.core.js.ast.JsProgram
import com.mohnishraj.aether.core.js.ast.JsStatement

object JsInspector {
    fun summary(result: JsEvaluationResult): String = buildString {
        appendLine("success=${result.success} tokens=${result.tokenCount} astNodes=${result.astNodeCount}")
        appendLine("steps=${result.steps} tasks=${result.tasksExecuted} elapsedMs=${"%.3f".format(result.elapsedNanos / 1_000_000.0)}")
        appendLine("value=${result.value.debugString()}")
        append("issues=${result.issues.size} outputLines=${result.output.size}")
    }

    fun tokens(tokens: List<JsToken>, maxTokens: Int = 200): String = buildString {
        tokens.take(maxTokens).forEachIndexed { index, token ->
            append(index.toString().padStart(4))
            append("  ")
            append(token.type.name.padEnd(18))
            append(" @${token.span.start.line}:${token.span.start.column}")
            if (token.lexeme.isNotEmpty()) append("  ${token.lexeme.replace("\n", "\\n").take(80)}")
            appendLine()
        }
        if (tokens.size > maxTokens) append("… ${tokens.size - maxTokens} more tokens")
    }

    fun ast(parse: JsParseResult, maxDepth: Int = 40): String = ast(parse.program, maxDepth)

    fun ast(program: JsProgram, maxDepth: Int = 40): String = buildString { render(program, 0, maxDepth, this) }

    private fun render(node: JsNode, depth: Int, maxDepth: Int, output: StringBuilder) {
        repeat(depth) { output.append("  ") }
        if (depth >= maxDepth) { output.appendLine("…"); return }
        when (node) {
            is JsProgram -> {
                output.appendLine("Program statements=${node.statements.size}")
                node.statements.forEach { render(it, depth + 1, maxDepth, output) }
            }
            is JsStatement.Empty -> output.appendLine("EmptyStatement")
            is JsStatement.Expression -> { output.appendLine("ExpressionStatement"); render(node.expression, depth + 1, maxDepth, output) }
            is JsStatement.Block -> { output.appendLine("BlockStatement statements=${node.statements.size}"); node.statements.forEach { render(it, depth + 1, maxDepth, output) } }
            is JsStatement.VariableDeclaration -> {
                output.appendLine("VariableDeclaration ${node.kind}")
                node.declarations.forEach { declaration ->
                    repeat(depth + 1) { output.append("  ") }; output.appendLine("Declarator ${declaration.name}")
                    declaration.initializer?.let { render(it, depth + 2, maxDepth, output) }
                }
            }
            is JsStatement.FunctionDeclaration -> {
                output.appendLine("FunctionDeclaration ${node.name}(${node.parameters.joinToString(", ")})")
                render(node.body, depth + 1, maxDepth, output)
            }
            is JsStatement.Return -> { output.appendLine("ReturnStatement"); node.argument?.let { render(it, depth + 1, maxDepth, output) } }
            is JsStatement.If -> {
                output.appendLine("IfStatement")
                render(node.test, depth + 1, maxDepth, output)
                render(node.consequent, depth + 1, maxDepth, output)
                node.alternate?.let { render(it, depth + 1, maxDepth, output) }
            }
            is JsStatement.While -> { output.appendLine("WhileStatement"); render(node.test, depth + 1, maxDepth, output); render(node.body, depth + 1, maxDepth, output) }
            is JsStatement.For -> {
                output.appendLine("ForStatement")
                node.initializer?.let { render(it, depth + 1, maxDepth, output) }
                node.test?.let { render(it, depth + 1, maxDepth, output) }
                node.update?.let { render(it, depth + 1, maxDepth, output) }
                render(node.body, depth + 1, maxDepth, output)
            }
            is JsStatement.ForOf -> {
                output.appendLine("ForOfStatement ${node.kind} ${node.variableName}")
                render(node.iterable, depth + 1, maxDepth, output)
                render(node.body, depth + 1, maxDepth, output)
            }
            is JsStatement.Throw -> { output.appendLine("ThrowStatement"); render(node.argument, depth + 1, maxDepth, output) }
            is JsStatement.Try -> {
                output.appendLine("TryStatement catch=${node.catchParameter.orEmpty()} finally=${node.finalizer != null}")
                render(node.block, depth + 1, maxDepth, output)
                node.catchBlock?.let { render(it, depth + 1, maxDepth, output) }
                node.finalizer?.let { render(it, depth + 1, maxDepth, output) }
            }
            is JsStatement.Break -> output.appendLine("BreakStatement")
            is JsStatement.Continue -> output.appendLine("ContinueStatement")
            is JsExpression.Literal -> output.appendLine("Literal ${node.value.debugString()}")
            is JsExpression.Identifier -> output.appendLine("Identifier ${node.name}")
            is JsExpression.ArrayLiteral -> { output.appendLine("ArrayExpression elements=${node.elements.size}"); node.elements.forEach { it?.let { item -> render(item, depth + 1, maxDepth, output) } } }
            is JsExpression.ObjectLiteral -> {
                output.appendLine("ObjectExpression properties=${node.properties.size}")
                node.properties.forEach { property ->
                    repeat(depth + 1) { output.append("  ") }; output.appendLine("Property ${property.key}")
                    render(property.value, depth + 2, maxDepth, output)
                }
            }
            is JsExpression.FunctionExpression -> { output.appendLine("FunctionExpression ${node.name.orEmpty()}(${node.parameters.joinToString(", ")})"); render(node.body, depth + 1, maxDepth, output) }
            is JsExpression.Unary -> { output.appendLine("UnaryExpression ${node.operator}"); render(node.argument, depth + 1, maxDepth, output) }
            is JsExpression.Update -> { output.appendLine("UpdateExpression ${node.operator} prefix=${node.prefix}"); render(node.argument, depth + 1, maxDepth, output) }
            is JsExpression.Binary -> { output.appendLine("BinaryExpression ${node.operator}"); render(node.left, depth + 1, maxDepth, output); render(node.right, depth + 1, maxDepth, output) }
            is JsExpression.Logical -> { output.appendLine("LogicalExpression ${node.operator}"); render(node.left, depth + 1, maxDepth, output); render(node.right, depth + 1, maxDepth, output) }
            is JsExpression.Assignment -> { output.appendLine("AssignmentExpression ${node.operator}"); render(node.target, depth + 1, maxDepth, output); render(node.value, depth + 1, maxDepth, output) }
            is JsExpression.Conditional -> { output.appendLine("ConditionalExpression"); render(node.test, depth + 1, maxDepth, output); render(node.consequent, depth + 1, maxDepth, output); render(node.alternate, depth + 1, maxDepth, output) }
            is JsExpression.Member -> { output.appendLine("MemberExpression computed=${node.computed}"); render(node.target, depth + 1, maxDepth, output); render(node.property, depth + 1, maxDepth, output) }
            is JsExpression.Call -> { output.appendLine("CallExpression args=${node.arguments.size}"); render(node.callee, depth + 1, maxDepth, output); node.arguments.forEach { render(it, depth + 1, maxDepth, output) } }
            is JsExpression.New -> { output.appendLine("NewExpression args=${node.arguments.size}"); render(node.constructor, depth + 1, maxDepth, output); node.arguments.forEach { render(it, depth + 1, maxDepth, output) } }
        }
    }

    fun value(value: JsValue): String = value.debugString()
}
