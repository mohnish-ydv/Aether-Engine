package com.mohnishraj.aether.core.js.ast

import com.mohnishraj.aether.core.js.JsSourceSpan
import com.mohnishraj.aether.core.js.JsValue

sealed class JsNode(open val span: JsSourceSpan)

data class JsProgram(val statements: List<JsStatement>, override val span: JsSourceSpan) : JsNode(span)

sealed class JsStatement(override val span: JsSourceSpan) : JsNode(span) {
    data class Empty(override val span: JsSourceSpan) : JsStatement(span)
    data class Expression(val expression: JsExpression, override val span: JsSourceSpan) : JsStatement(span)
    data class Block(val statements: List<JsStatement>, override val span: JsSourceSpan) : JsStatement(span)
    data class VariableDeclaration(val kind: VariableKind, val declarations: List<VariableDeclarator>, override val span: JsSourceSpan) : JsStatement(span)
    data class FunctionDeclaration(val name: String, val parameters: List<String>, val body: Block, override val span: JsSourceSpan) : JsStatement(span)
    data class Return(val argument: JsExpression?, override val span: JsSourceSpan) : JsStatement(span)
    data class If(val test: JsExpression, val consequent: JsStatement, val alternate: JsStatement?, override val span: JsSourceSpan) : JsStatement(span)
    data class While(val test: JsExpression, val body: JsStatement, override val span: JsSourceSpan) : JsStatement(span)
    data class For(
        val initializer: JsStatement?,
        val test: JsExpression?,
        val update: JsExpression?,
        val body: JsStatement,
        override val span: JsSourceSpan
    ) : JsStatement(span)
    data class Break(override val span: JsSourceSpan) : JsStatement(span)
    data class Continue(override val span: JsSourceSpan) : JsStatement(span)
    data class Throw(val argument: JsExpression, override val span: JsSourceSpan) : JsStatement(span)
    data class Try(
        val block: Block,
        val catchParameter: String?,
        val catchBlock: Block?,
        val finalizer: Block?,
        override val span: JsSourceSpan
    ) : JsStatement(span)
    data class ForOf(
        val kind: VariableKind,
        val variableName: String,
        val iterable: JsExpression,
        val body: JsStatement,
        override val span: JsSourceSpan
    ) : JsStatement(span)
}

enum class VariableKind { LET, CONST, VAR }

data class VariableDeclarator(val name: String, val initializer: JsExpression?, val span: JsSourceSpan)

sealed class JsExpression(override val span: JsSourceSpan) : JsNode(span) {
    data class Literal(val value: JsValue, override val span: JsSourceSpan) : JsExpression(span)
    data class Identifier(val name: String, override val span: JsSourceSpan) : JsExpression(span)
    data class ArrayLiteral(val elements: List<JsExpression?>, override val span: JsSourceSpan) : JsExpression(span)
    data class ObjectLiteral(val properties: List<ObjectProperty>, override val span: JsSourceSpan) : JsExpression(span)
    data class FunctionExpression(val name: String?, val parameters: List<String>, val body: JsStatement.Block, override val span: JsSourceSpan) : JsExpression(span)
    data class Unary(val operator: String, val argument: JsExpression, override val span: JsSourceSpan) : JsExpression(span)
    data class Update(val operator: String, val argument: JsExpression, val prefix: Boolean, override val span: JsSourceSpan) : JsExpression(span)
    data class Binary(val left: JsExpression, val operator: String, val right: JsExpression, override val span: JsSourceSpan) : JsExpression(span)
    data class Logical(val left: JsExpression, val operator: String, val right: JsExpression, override val span: JsSourceSpan) : JsExpression(span)
    data class Assignment(val target: JsExpression, val operator: String, val value: JsExpression, override val span: JsSourceSpan) : JsExpression(span)
    data class Conditional(val test: JsExpression, val consequent: JsExpression, val alternate: JsExpression, override val span: JsSourceSpan) : JsExpression(span)
    data class Member(val target: JsExpression, val property: JsExpression, val computed: Boolean, override val span: JsSourceSpan) : JsExpression(span)
    data class Call(val callee: JsExpression, val arguments: List<JsExpression>, override val span: JsSourceSpan) : JsExpression(span)
    data class New(val constructor: JsExpression, val arguments: List<JsExpression>, override val span: JsSourceSpan) : JsExpression(span)
}

data class ObjectProperty(val key: String, val value: JsExpression, val span: JsSourceSpan)
